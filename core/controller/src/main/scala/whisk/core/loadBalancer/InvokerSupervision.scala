/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.loadBalancer

import java.nio.charset.StandardCharsets
import scala.math._
import scala.BigDecimal._
import scala.collection.immutable
import scala.concurrent.Future
// import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import org.apache.kafka.clients.producer.RecordMetadata
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.FSM
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.actor.Props
import akka.pattern.pipe
// import akka.pattern.ask
import akka.util.Timeout
import whisk.common.AkkaLogging
import whisk.common.LoggingMarkers
import whisk.common.RingBuffer
import whisk.common.TransactionId
import whisk.core.connector._
import whisk.core.entitlement.Privilege
import whisk.core.entity.ActivationId.ActivationIdGenerator
import whisk.core.entity._
import whisk.utils.Aggregator
import whisk.utils.DockerInterval
// Received events
case object GetStatus

case object Tick

// States an Invoker can be in
sealed trait InvokerState { val asString: String }
case object Offline extends InvokerState { val asString = "down" }
case object Healthy extends InvokerState { val asString = "up" }
case object UnHealthy extends InvokerState { val asString = "unhealthy" }

case class ActivationProfile(cpuPerc: BigDecimal, ioThroughput: BigDecimal, networkThroughput: BigDecimal, n: BigDecimal)

case class ActivationRequest(msg: ActivationMessage, invoker: InstanceId)
case class AnalyzeActivation(profile: ActivationProfile)
case class InstanceHappiness(instance: InstanceId, happiness: Double)
case class InvocationFinishedMessage(invokerInstance: InstanceId, successful: Boolean)
case class DockerIntervalMessage(intervalMap: Map[String, DockerInterval])

// Data stored in the Invoker
final case class InvokerInfo(buffer: RingBuffer[Boolean])

/**
 * Actor representing a pool of invokers
 *
 * The InvokerPool manages a Invokers through subactors. An new Invoker
 * is registered lazily by sending it a Ping event with the name of the
 * Invoker. Ping events are furthermore forwarded to the respective
 * Invoker for their respective State handling.
 *
 * Note: An Invoker that never sends an initial Ping will not be considered
 * by the InvokerPool and thus might not be caught by monitoring.
 */
class InvokerPool(childFactory: (ActorRefFactory, InstanceId) => ActorRef,
                  sendActivationToInvoker: (ActivationMessage, InstanceId) => Future[RecordMetadata],
                  pingConsumer: MessageConsumer)
    extends Actor {

  implicit val transid = TransactionId.invokerHealth
  implicit val logging = new AkkaLogging(context.system.log)
  implicit val timeout = Timeout(5.seconds)
  implicit val ec = context.dispatcher

  // State of the actor. Mutable vars with immutable collections prevents closures or messages
  // from leaking the state for external mutation
  var instanceToRef = immutable.Map.empty[InstanceId, ActorRef]
  var refToInstance = immutable.Map.empty[ActorRef, InstanceId]
  var status = IndexedSeq[(InstanceId, InvokerState)]()

  def receive = {
    case p: PingMessage =>
      val invoker = instanceToRef.getOrElse(p.instance, registerInvoker(p.instance))
      instanceToRef = instanceToRef.updated(p.instance, invoker)
      p.profile match {
        case Some(prof) => 
          logging.info(this, s"received invoker $p.instance ")
          invoker.forward(DockerIntervalMessage(prof))
        case None => 
          logging.info(this, "received invoker ping")
      }

      invoker.forward(p)

    case GetStatus => sender() ! status

    case msg: InvocationFinishedMessage =>
      // Forward message to invoker, if InvokerActor exists
      instanceToRef.get(msg.invokerInstance).foreach(_.forward(msg))

    case CurrentState(invoker, currentState: InvokerState) =>
      refToInstance.get(invoker).foreach { instance =>
        status = status.updated(instance.toInt, (instance, currentState))
      }
      logStatus()

    case Transition(invoker, oldState: InvokerState, newState: InvokerState) =>
      refToInstance.get(invoker).foreach { instance =>
        status = status.updated(instance.toInt, (instance, newState))
      }
      logStatus()

    case AnalyzeActivation(activation) =>
      val sendBack = sender()
      context.system.actorOf(Props {
        new ActivationAnalyzer(sendBack, activation, instanceToRef)
      })

    // this is only used for the internal test action which enabled an invoker to become healthy again
    case msg: ActivationRequest => sendActivationToInvoker(msg.msg, msg.invoker).pipeTo(sender)
  }

  def logStatus() = {
    val pretty = status.map { case (instance, state) => s"${instance.toInt} -> $state" }
    logging.info(this, s"invoker status changed to ${pretty.mkString(", ")}")
  }

  /** Receive Ping messages from invokers. */
  val pingPollDuration = 1.second
  val invokerPingFeed = context.system.actorOf(Props {
    new MessageFeed(
      "ping",
      logging,
      pingConsumer,
      pingConsumer.maxPeek,
      pingPollDuration,
      processInvokerPing,
      logHandoff = false)
  })

  def processInvokerPing(bytes: Array[Byte]): Future[Unit] = Future {
    val raw = new String(bytes, StandardCharsets.UTF_8)
    PingMessage.parse(raw) match {
      case Success(p: PingMessage) =>
        // swap in the new values for profile information
        //logging.info(this, s"received from ping: $raw")
        self ! p
        invokerPingFeed ! MessageFeed.Processed

      case Failure(t) =>
        invokerPingFeed ! MessageFeed.Processed
        logging.error(this, s"failed processing message: $raw with $t")
    }
  }

  /** Pads a list to a given length using the given function to compute entries */
  def padToIndexed[A](list: IndexedSeq[A], n: Int, f: (Int) => A) = list ++ (list.size until n).map(f)

  // Register a new invoker
  def registerInvoker(instanceId: InstanceId): ActorRef = {
    logging.info(this, s"registered a new invoker: invoker${instanceId.toInt}")(TransactionId.invokerHealth)

    status = padToIndexed(status, instanceId.toInt + 1, i => (InstanceId(i), Offline))

    val ref = childFactory(context, instanceId)

    ref ! SubscribeTransitionCallBack(self) // register for state change events

    refToInstance = refToInstance.updated(ref, instanceId)

    ref
  }

}

class ActivationAnalyzer(sendBack: ActorRef, activation: ActivationProfile, 
                         instanceToRef: Map[InstanceId, ActorRef])
    extends Actor with Aggregator {
  implicit val ec = context.dispatcher
  implicit val logging = new AkkaLogging(context.system.log)
  var result: Option[InstanceHappiness] = None
  var count: Int = 0

  private case object TimeOut

  if (instanceToRef.size > 0)
    for ((instanceId, ref) <- instanceToRef) {
      ref ! activation
      expectOnce {
        case i: InstanceHappiness if i.instance == instanceId =>
            result match {
              case Some(best) =>
                if (i.happiness > best.happiness)
                  result = Some(i)
              case None =>
                if (i.happiness > 0)
                  result = Some(i)
            }
            count += 1
            logging.info(this, s"received happiness from $instanceId, count = $count")
            returnResult()
      }
    }
  else returnResult()

  context.system.scheduler.scheduleOnce(1.second, self, TimeOut)
  expect {
    case TimeOut =>
      logging.info(this, s"Analyzer timed out")
      returnResult(true)
  }

  def returnResult(force: Boolean = false) {
    if (count == instanceToRef.size || force) {
      logging.info(this, "sending back to caller")
      sendBack ! (result match {
        case Some(best) => Some(best.instance)
        case None => None
      })
      context.stop(self)
    }
  }
}

object InvokerPool {
  def props(f: (ActorRefFactory, InstanceId) => ActorRef,
            p: (ActivationMessage, InstanceId) => Future[RecordMetadata],
            pc: MessageConsumer) = {
    Props(new InvokerPool(f, p, pc))
  }

  /** A stub identity for invoking the test action. This does not need to be a valid identity. */
  val healthActionIdentity = {
    val whiskSystem = "whisk.system"
    Identity(Subject(whiskSystem), EntityName(whiskSystem), AuthKey(UUID(), Secret()), Set[Privilege]())
  }

  /** An action to use for monitoring invoker health. */
  def healthAction(i: InstanceId) = ExecManifest.runtimesManifest.resolveDefaultRuntime("nodejs:6").map { manifest =>
    new WhiskAction(
      namespace = healthActionIdentity.namespace.toPath,
      name = EntityName(s"invokerHealthTestAction${i.toInt}"),
      exec = CodeExecAsString(manifest, """function main(params) { return params; }""", None))
  }
}

/**
 * Actor representing an Invoker
 *
 * This finite state-machine represents an Invoker in its possible
 * states "Healthy" and "Offline".
 */
class InvokerActor(invokerInstance: InstanceId, controllerInstance: InstanceId) extends FSM[InvokerState, InvokerInfo] {
  implicit val transid = TransactionId.invokerHealth
  implicit val logging = new AkkaLogging(context.system.log)
  val name = s"invoker${invokerInstance.toInt}"
  var invokerProfile = immutable.Map.empty[String, DockerInterval]

  val healthyTimeout = 10.seconds

  // This is done at this point to not intermingle with the state-machine
  // especially their timeouts.
  def customReceive: Receive = {
    case _: RecordMetadata => // The response of putting testactions to the MessageProducer. We don't have to do anything with them.
  }
  override def receive = customReceive.orElse(super.receive)

  /**
   *  Always start UnHealthy. Then the invoker receives some test activations and becomes Healthy.
   */
  startWith(UnHealthy, InvokerInfo(new RingBuffer[Boolean](InvokerActor.bufferSize)))

  /**
   * An Offline invoker represents an existing but broken
   * invoker. This means, that it does not send pings anymore.
   */
  when(Offline) {
    case Event(_: PingMessage, _) => goto(UnHealthy)
    case Event(profile: ActivationProfile, _) =>
      sender() ! InstanceHappiness(invokerInstance, -1.0)
      stay
  }

  /**
   * An UnHealthy invoker represents an invoker that was not able to handle actions successfully.
   */
  when(UnHealthy, stateTimeout = healthyTimeout) {
    case Event(_: PingMessage, _) => stay
    case Event(StateTimeout, _)   => goto(Offline)
    case Event(Tick, info) => {
      invokeTestAction()
      stay
    }
    case Event(profile: ActivationProfile, _) =>
      sender() ! InstanceHappiness(invokerInstance, -1.0)
      stay
  }

  /**
   * A Healthy invoker is characterized by continuously getting
   * pings. It will go offline if that state is not confirmed
   * for 20 seconds.
   */
  when(Healthy, stateTimeout = healthyTimeout) {
    case Event(_: PingMessage, _) => stay
    case Event(StateTimeout, _)   => goto(Offline)
    case Event(profile: ActivationProfile, _) =>
      logging.info(this, s"processing activation profile")
      sender() ! InstanceHappiness(invokerInstance, calculateHappiness(profile))
      stay
  }

  /**
   * Handle the completion of an Activation in every state.
   */
  whenUnhandled {
    case Event(cm: InvocationFinishedMessage, info) => handleCompletionMessage(cm.successful, info.buffer)
    case Event(msg: DockerIntervalMessage, _) => 
      invokerProfile = msg.intervalMap
      stay
  }

  /** Logging on Transition change */
  onTransition {
    case _ -> Offline =>
      transid.mark(
        this,
        LoggingMarkers.LOADBALANCER_INVOKER_OFFLINE,
        s"$name is offline",
        akka.event.Logging.WarningLevel)
    case _ -> UnHealthy =>
      transid.mark(
        this,
        LoggingMarkers.LOADBALANCER_INVOKER_UNHEALTHY,
        s"$name is unhealthy",
        akka.event.Logging.WarningLevel)
    case _ -> Healthy => logging.info(this, s"$name is healthy")
  }

  /** Scheduler to send test activations when the invoker is unhealthy. */
  onTransition {
    case _ -> UnHealthy => {
      invokeTestAction()
      setTimer(InvokerActor.timerName, Tick, 1.minute, true)
    }
    case UnHealthy -> _ => cancelTimer(InvokerActor.timerName)
  }

  initialize()

  /**
   * Handling for active acks. This method saves the result (successful or unsuccessful)
   * into an RingBuffer and checks, if the InvokerActor has to be changed to UnHealthy.
   *
   * @param wasActivationSuccessful: result of Activation
   * @param buffer to be used
   */
  private def handleCompletionMessage(wasActivationSuccessful: Boolean, buffer: RingBuffer[Boolean]) = {
    buffer.add(wasActivationSuccessful)

    // If the action is successful it seems like the Invoker is Healthy again. So we execute immediately
    // a new test action to remove the errors out of the RingBuffer as fast as possible.
    // The actions that arrive while the invoker is unhealthy are most likely health actions.
    // It is possible they are normal user actions as well. This can happen if such actions were in the
    // invoker queue or in progress while the invoker's status flipped to Unhealthy.
    if (wasActivationSuccessful && stateName == UnHealthy) {
      invokeTestAction()
    }

    // Stay in online if the activations was successful.
    // Stay in offline, if an activeAck reaches the controller.
    if ((stateName == Healthy && wasActivationSuccessful) || stateName == Offline) {
      stay
    } else {
      // Goto UnHealthy if there are more errors than accepted in buffer, else goto Healthy
      if (buffer.toList.count(_ == true) >= InvokerActor.bufferSize - InvokerActor.bufferErrorTolerance) {
        gotoIfNotThere(Healthy)
      } else {
        gotoIfNotThere(UnHealthy)
      }
    }
  }

  /**
   * Creates an activation request with the given action and sends it to the InvokerPool.
   * The InvokerPool redirects it to the invoker which is represented by this InvokerActor.
   */
  private def invokeTestAction() = {
    InvokerPool.healthAction(controllerInstance).map { action =>
      val activationMessage = ActivationMessage(
        // Use the sid of the InvokerSupervisor as tid
        transid = transid,
        action = action.fullyQualifiedName(true),
        // Use empty DocRevision to force the invoker to pull the action from db all the time
        revision = DocRevision.empty,
        user = InvokerPool.healthActionIdentity,
        // Create a new Activation ID for this activation
        activationId = new ActivationIdGenerator {}.make(),
        activationNamespace = action.namespace,
        rootControllerIndex = controllerInstance,
        blocking = false,
        content = None)

      context.parent ! ActivationRequest(activationMessage, invokerInstance)
    }
  }

  /**
   * Only change the state if the currentState is not the newState.
   *
   * @param newState of the InvokerActor
   */
  private def gotoIfNotThere(newState: InvokerState) = {
    if (stateName == newState) stay() else goto(newState)
  }
  
  /**
   * Calculate happiness based on latest profile
   */
  private def calculateHappiness(activationProfile: ActivationProfile): Double = {
    var happiness: Double = 0
    logging.info(this, s"calculating happiness for invoker $invokerInstance")
    // Add ioHappiness
    val ioNorm: Double = 1024 * 200
    var ioHappiness: Double = activationProfile.ioThroughput.doubleValue()
    invokerProfile map { case (_, v) =>
      ioHappiness = ioHappiness + v.ioThroughput.doubleValue()
    }
    ioHappiness = 1 - (ioHappiness / ioNorm)*(ioHappiness/ioNorm)
    logging.info(this, s"calculated ioHappiness: $ioHappiness")
    happiness = happiness + ioHappiness

    // Add networkHappiness
    val networkNorm: Double = 1024 * 200
    var networkHappiness: Double = activationProfile.networkThroughput.doubleValue()
    invokerProfile map { case (_, v) =>
      networkHappiness = networkHappiness + v.networkThroughput.doubleValue()
    }
    networkHappiness = 1 - (networkHappiness / networkNorm)*(networkHappiness/networkNorm)
    logging.info(this, s"calculated networkHappiness: $networkHappiness")
    happiness = happiness + networkHappiness

    // Calculate CPU happniess
    logging.info(this, s"calculating cpuHappiness")
    var cpuHappiness: Double = 0

    val pFiltered: List[Double] = invokerProfile.map{ 
      case (_, v) => v.cpuPerc.doubleValue()
    }
    .filter{_ != 0}.toList // not 0

    // Calculate p*
    val eps = 0.05
    var maxP : Double = 0.0
    if (pFiltered.length != 0) { maxP = pFiltered.reduceLeft(_ max _)}
    val p_star: List[Double] = pFiltered.map{p_val => {
       if (maxP - p_val <= eps) 1
       else p_val
    }}.toList

    val p = activationProfile.cpuPerc.doubleValue() :: p_star // desired results of each job
    logging.info(this, s"p: $p")
    var shareLeft = 1.0

    var unsat = p.length
    var n = p.length
    val pp = Array.fill[Double](n)(0)
    while (shareLeft > 1e-5 && unsat > 0) {
      logging.info(this, s"| spliting shareLeft $shareLeft among $unsat unsatisfied")
      val shareToGive: Double = shareLeft / unsat
      for (i <- 0 until n) {
        if (pp(i) < p(i)) {
          if (shareToGive < p(i) - pp(i)) {
            pp(i) = pp(i) + shareToGive
            logging.info(this, s"| | activation $i not satisfied (${pp(i)} / ${p(i)})")
            shareLeft = shareLeft - shareToGive
          } else {
            pp(i) = p(i)
            logging.info(this, s"| | activation $i satisfied (${p(i)})")
            unsat -= 1
            shareLeft = shareLeft - (p(i) - pp(i))
          }
        }
      }
    }

    for (i <- 0 until n) {
      if (p(i) > 0) {
        cpuHappiness = cpuHappiness + sqrt((pp(i)/p(i)))
        //cpuHappiness = cpuHappiness + (pp(i) * pp(i) / p(i) / p(i))
      }
    }
    cpuHappiness = cpuHappiness / p.length
    logging.info(this, s"calculated cpuHappiness: $cpuHappiness for $invokerInstance")
    happiness = happiness + cpuHappiness
    logging.info(this, s"calculated totalHappiness: $happiness for $invokerInstance")
    happiness
  }
}

object InvokerActor {
  def props(invokerInstance: InstanceId, controllerInstance: InstanceId) =
    Props(new InvokerActor(invokerInstance, controllerInstance))

  val bufferSize = 10
  val bufferErrorTolerance = 3

  val timerName = "testActionTimer"
}



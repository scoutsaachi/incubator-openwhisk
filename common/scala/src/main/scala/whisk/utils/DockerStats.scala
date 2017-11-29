package whisk.utils

import spray.json._
import java.time.OffsetDateTime
import sys.process._
import scala.language.postfixOps
import java.time.Duration
import scala.util.{Try, Success, Failure}

case class DockerProfile(name: String, readTime: OffsetDateTime, cpuPerc: BigDecimal,
                         totalIo: BigDecimal, networkUsage: BigDecimal,
                         containerCreated: Option[OffsetDateTime])

// representes profile displacement stats
// throughputs are per bytes/ms
case class DockerInterval(name: String, cpuPerc: BigDecimal, ioThroughput: BigDecimal,
                          networkThroughput: BigDecimal)

object DockerStats {

    def computeNewDockerSummary(oldMap: Map[String, DockerProfile], newMap: Map[String, DockerProfile]) : Map[String, DockerInterval] = {
        (for ((name, newProf) <- newMap) yield {
            val interval = oldMap.get(name) match {
                case Some(oldProf) => {
                    createDockerInterval(newProf, oldProf)
                }
                case None => {
                    createStartingInterval(newProf)
                }
            }
            name -> interval
        }).toMap
    }

    // create an interval with respect to a starting point
    def createDockerInterval(prof: DockerProfile, oldP: DockerProfile) : DockerInterval = {
        val time_interval = Duration.between(oldP.readTime, prof.readTime).toMillis()
        val cpu_perc = prof.cpuPerc
        val io_thrpt : BigDecimal = (prof.totalIo - oldP.totalIo)/time_interval
        val network_thrpt : BigDecimal = (prof.networkUsage - oldP.networkUsage)/time_interval
        DockerInterval(prof.name, cpu_perc, io_thrpt, network_thrpt)
    }

    // creating an interval when no prior start point exists
    def createStartingInterval(prof: DockerProfile) : DockerInterval = {
        val create_time = prof.containerCreated.getOrElse(prof.readTime.minusSeconds(1))
        val time_interval = Duration.between(create_time, prof.readTime).toMillis()
        val cpu_perc = prof.cpuPerc
        val io_thrpt : BigDecimal = prof.totalIo/time_interval
        val network_thrpt : BigDecimal = prof.networkUsage/time_interval
        DockerInterval(prof.name, cpu_perc, io_thrpt, network_thrpt)
    }

    // Drill down into a json. All values in keys are keys for nested objects, the finalKey is the
    // key for the final value
    def getNestedJSValue(keys: List[String], finalKey: String, obj: Option[JsObject]) : Option[JsValue] = {
       var curr_obj = obj
       keys.foreach (k => curr_obj = curr_obj.flatMap(_.fields.get(k)).map(_.asInstanceOf[JsObject]))
       val result = curr_obj.flatMap(_.fields.get(finalKey)).map(_.asInstanceOf[JsValue])
       result
    }

    // Given a docker response corresponding to a name create the docker profile
    def extractMetricsFromResp(name: String, resp: String, containerCreated: Option[OffsetDateTime]): DockerProfile = {
       val jsonObject = Option(resp.parseJson).map(_.asInstanceOf[JsObject])
       val readVal = getNestedJSValue(List(), "read", jsonObject).map(_.asInstanceOf[JsString].value)
       val readTime = readVal.map(OffsetDateTime.parse(_)).getOrElse(OffsetDateTime.now())
       // cpu
       val activation_usage = getNestedJSValue(List("cpu_stats", "cpu_usage"), "total_usage", jsonObject).map(_.asInstanceOf[JsNumber]).getOrElse(JsNumber(0)).value
       val system_usage = getNestedJSValue(List("cpu_stats"), "system_cpu_usage", jsonObject).map(_.asInstanceOf[JsNumber]).getOrElse(JsNumber(0)).value
       // precpu
       val pre_activation_usage = getNestedJSValue(List("precpu_stats", "cpu_usage"), "total_usage", jsonObject).map(_.asInstanceOf[JsNumber]).getOrElse(JsNumber(0)).value
       val pre_system_usage = getNestedJSValue(List("precpu_stats"), "system_cpu_usage", jsonObject).map(_.asInstanceOf[JsNumber]).getOrElse(JsNumber(0)).value
       // cpu_perc
       val cpu_perc = (activation_usage - pre_activation_usage)/(system_usage - pre_system_usage)
 
       // io
       val io_stats = getNestedJSValue(List("blkio_stats"), "io_service_bytes_recursive", jsonObject).map(_.asInstanceOf[JsArray])
       val totalIoDict = io_stats.flatMap(_.elements.lastOption).map(_.asInstanceOf[JsObject])
       val totalIo = getNestedJSValue(List(), "value", totalIoDict).map(_.asInstanceOf[JsNumber]).getOrElse(JsNumber(0)).value
       //network
       val network_dict = getNestedJSValue(List(), "networks", jsonObject).getOrElse(new JsObject(Map())).asInstanceOf[JsObject] // JsObject
       val network_byte_values = (network_dict.fields.values
            .map(_.asInstanceOf[JsObject])
            .map(x => getNestedJSValue(List(), "rx_bytes", Option(x)).map(_.asInstanceOf[JsNumber]).getOrElse(JsNumber(0))).map(_.value))
       val networkBytes = network_byte_values.sum
       DockerProfile(name, readTime, cpu_perc, totalIo, networkBytes, containerCreated)
    }

    // Get the docker profile corresponding to a name
    def getExactContainerStat(name: String, containerCreated: Option[OffsetDateTime]) : Option[DockerProfile] = {
       val respTry = Try(scala.io.Source.fromURL(s"http://0.0.0.0:4243/containers/$name/stats?stream=false").mkString)
       respTry match {
          case Success(resp) => Option(extractMetricsFromResp(name, resp, containerCreated))
          case Failure(e) => None
       }
    }

    // Get all docker profiles of running containers
    def getAllStats()(implicit ec: ExecutionContext) : Map[String, DockerProfile] = {
	val dockerIds : String = "docker ps -q  --filter name=wsk" !!
        val dockerNamesTimes : String = s"sudo docker inspect -f {{.Name}},{{.State.StartedAt}} $dockerIds" !!
        val dockerNamesTimesSeq = dockerNamesTimes.split("\n").toList.map(_.split(",").toList)
        val futureList = (for(dockerNameTime <- dockerNamesTimesSeq) yield Future{
            val dockerName = dockerNameTime(0).substring(1)
            val dockerTime = OffsetDateTime.parse(dockerNameTime(1))
            var prof = getExactContainerStat(dockerName, Option(dockerTime)) // Option[DockerProfile]
        }).toList
        val futureSeq = Future.sequence(futureList)
        val collectedProfiles = Await.ready(f, Duration.Inf).value.get // List of Option[DockerProfile]
        collectedProfiles.flatten.map(_.name -> _).toMap
    }
}

package whisk.utils

import spray.json._
import sys.process._
import scala.language.postfixOps

//case class DockerProfile(name: String, read: String, preread: String, cpu_stats: JsObject, memory_stats: JsObject, networks: JsObject)

object DockerStats {
    def queryJSObject(key: String, obj: JsObject) : String = obj.fields.get(key) match {
            case Some(x) => x.toString
            case None => "null"
        }

    def getIds() : List[Map[String, String]] = {
        val dockerIDs : String = "sudo docker ps -q --filter 'name=wsk'" !!
        val startList = List(Map("ids" -> dockerIDs))
        startList
       // val dockerIDSeq = dockerIDs.split("\n").toList
       // val jsonStrings = dockerIDSeq.map(x => scala.io.Source.fromURL("http://0.0.0.0:4243/containers/${x}/stats?stream=false").mkString)
       // val jsonObjects = jsonStrings.map(x => x.parseJson.asInstanceOf[JsObject])
       // val jsonMaps = for(jsonObject <- jsonObjects) yield {
       //     val readVal = queryJSObject("read", jsonObject)
       //     Map("read" -> readVal)
       // }
       // jsonMaps
    }
}

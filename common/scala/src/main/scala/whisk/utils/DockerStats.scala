package whisk.utils

import spray.json._
//import sys.process._
//import scala.language.postfixOps

case class DockerProfile(name: String, readTime: Option[String], activationUsage: BigDecimal,
                         systemUsage: BigDecimal, totalIo: BigDecimal, networkUsage: BigDecimal)

object DockerStats {


    def getNestedJSValue(keys: List[String], finalKey: String, obj: Option[JsObject]) : Option[JsValue] = {
       var curr_obj = obj
       keys.foreach (k => curr_obj = curr_obj.map(_.fields.getOrElse(k, None)).map(_.asInstanceOf[JsObject]))
       val result = curr_obj.map(_.fields.getOrElse(finalKey, None)).map(_.asInstanceOf[JsValue])
       result
    }

    def getExactContainerStat(name: String) : DockerProfile = {
       val resp = scala.io.Source.fromURL(s"http://0.0.0.0:4243/containers/$name/stats?stream=false").mkString
       val jsonObject = Option(resp.parseJson).map(_.asInstanceOf[JsObject])
       val readVal = getNestedJSValue(List(), "read", jsonObject).map(_.toString)
       // cpu
       val activation_usage = getNestedJSValue(List("cpu_stats", "cpu_usage"), "total_usage", jsonObject).map(_.asInstanceOf[JsNumber]).getOrElse(JsNumber(0)).value
       val system_usage = getNestedJSValue(List("cpu_stats"), "system_cpu_usage", jsonObject).map(_.asInstanceOf[JsNumber]).getOrElse(JsNumber(0)).value
       // io
       val io_stats = getNestedJSValue(List("blkio_stats"), "io_service_bytes_recursive", jsonObject).map(_.asInstanceOf[JsArray])
       val totalIoDict = io_stats.map(_.elements.lastOption.getOrElse(None)).map(_.asInstanceOf[JsObject])
       val totalIo = getNestedJSValue(List(), "value", totalIoDict).map(_.asInstanceOf[JsNumber]).getOrElse(JsNumber(0)).value
       //network
       val network_dict = getNestedJSValue(List(), "networks", jsonObject).getOrElse(new JsObject(Map())).asInstanceOf[JsObject] // JsObject
       val network_byte_values = (network_dict.fields.values
            .map(_.asInstanceOf[JsObject])
            .map(x => getNestedJSValue(List(), "rx_bytes", Option(x)).map(_.asInstanceOf[JsNumber]).getOrElse(JsNumber(0))).map(_.value))
       val networkBytes = network_byte_values.sum
       DockerProfile(name, readVal, activation_usage, system_usage, totalIo, networkBytes)
    }

    //def getExact() : Map[String, DockerProfile] = {
    //    val dockerIDs : String = "sudo docker ps -q --filter name=wsk" !!
    //    val dockerIDSeq = dockerIDs.split("\n").toList
    //   // make this concurrent
    //    val jsonStrings = dockerIDSeq.map(x => scala.io.Source.fromURL(s"http://0.0.0.0:4243/containers/${x}/stats?stream=false").mkString)
    //    val jsonObjects = jsonStrings.map(x => Some(x.parseJson.asInstanceOf[JsObject])) // {Option[JsObject]}
    //    val jsonMaps = (for(jsonObject <- jsonObjects) yield {
    //        val name = getNestedJSValue(List(), "name", jsonObject).map(_.toString).getOrElse("no_name") // this is just a JsString
    //        val readVal = getNestedJSValue(List(), "read", jsonObject).map(_.toString)
            // cpu
    //        val activation_usage = getNestedJSValue(List("cpu_stats", "cpu_usage"), "total_usage", jsonObject).map(_.toString)
    //        val system_usage = getNestedJSValue(List("cpu_status"), "system_cpu_usage", jsonObject).map(_.toString)
            // io
    //        val io_stats = getNestedJSValue(List("blkio_stats"), "io_service_bytes_recursive", jsonObject).map(_.asInstanceOf[JsArray])
    //        val totalIoDict = io_stats.map(_.elements.lastOption).map(_.asInstanceOf[JsObject])
    //        val totalIo = getNestedJSValue(List(), "value", totalIoDict).map(_.toString)
            //network
     //       val network_usage = getNestedJSValue(List("network"), "rx_bytes", jsonObject).map(_.toString)
     //       name -> DockerProfile(name, readVal, activation_usage, system_usage, totalIo, network_usage)
     //   }).toMap
     //   jsonMaps
    //}
}

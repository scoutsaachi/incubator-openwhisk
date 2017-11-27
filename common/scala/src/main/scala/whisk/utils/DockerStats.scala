package whisk.utils

import spray.json._
import sys.process._
import scala.language.postfixOps

import whisk.common.Logging

object DockerStats {
    def getIds() : Array[spray.json.JsValue] = {
        val dockerIDs = "sudo docker ps -q --filter 'name=wsk'" !!
        val dockerIDSeq = dockerIDs.split("\n")
        val jsonStrings = dockerIDSeq.map(x => scala.io.Source.fromURL("http://0.0.0.0:4243/containers/${x}/stats?stream=false").mkString)
        val jsonObjects = jsonStrings.map(x => x.parseJson)
        jsonObjects
    }
}

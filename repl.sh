#!/bin/bash
sudo ./gradlew :repl:compileScala && \
java -Dscala.usejavacp=true \
     -classpath "$(gradle :repl:printClasspath --quiet)" \
     scala.tools.nsc.MainGenericRunner \
     -i repl.scala

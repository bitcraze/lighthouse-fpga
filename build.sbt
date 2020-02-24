name := "Lighthouse"

version := "1.0"

scalaVersion := "2.11.12"

fork := true

libraryDependencies ++= Seq(
  "com.github.spinalhdl" % "spinalhdl-core_2.11" % "1.3.7",
  "com.github.spinalhdl" % "spinalhdl-lib_2.11" % "1.3.7"
)

libraryDependencies += "com.github.tototoshi" %% "scala-csv" % "1.3.6"
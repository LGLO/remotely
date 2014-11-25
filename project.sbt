import oncue.build._

organization in Global := "oncue.svc.remotely"

scalaVersion in Global := "2.10.4"

resolvers += Resolver.sonatypeRepo("releases")

lazy val remotely = project.in(file(".")).aggregate(core, funnel, examples, `benchmark-server`, `benchmark-client`)

lazy val core = project

lazy val paradiseVersion = "2.0.1"

lazy val funnel = project.dependsOn(core)

lazy val examples = project.dependsOn(core)

lazy val `benchmark-protocol` = project.in(file("benchmark/protocol")).dependsOn(core)

lazy val `benchmark-client` = project.in(file("benchmark/client")).dependsOn(`benchmark-protocol`)

lazy val `benchmark-server` = project.in(file("benchmark/server")).dependsOn(`benchmark-protocol`)

OnCue.baseSettings

Publishing.ignore

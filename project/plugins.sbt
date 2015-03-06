addSbtPlugin("org.brianmckenna" % "sbt-wartremover" % "0.11")

addSbtPlugin("com.eed3si9n"     % "sbt-assembly"    % "0.11.2")

addSbtPlugin("com.typesafe.sbt" % "sbt-site"        % "0.8.1")

addSbtPlugin("com.typesafe.sbt" % "sbt-ghpages"     % "0.5.3")

addSbtPlugin("com.typesafe.sbt" % "sbt-osgi"        % "0.7.0")

resolvers += Resolver.url(
  "bintray-sbt-plugin-releases",
    url("http://dl.bintray.com/content/sbt/sbt-plugin-releases"))(
        Resolver.ivyStylePatterns)

addSbtPlugin("me.lessis"        % "bintray-sbt"     % "0.1.2")

resolvers += Resolver.url(
  "tpolecat-sbt-plugin-releases",
    url("http://dl.bintray.com/content/tpolecat/sbt-plugin-releases"))(
        Resolver.ivyStylePatterns)

addSbtPlugin("org.tpolecat"     % "tut-plugin"      % "0.3.1")

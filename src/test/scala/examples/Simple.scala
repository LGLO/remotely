package remotely
package examples

import java.net.InetSocketAddress
import scalaz.concurrent.Task

object Simple extends App {

  import codecs._

  def foo(i: Int): String = "BONUS"

  // on server, populate environment with codecs and values
  val env = Environment.empty
    .codec[Int]
    .codec[String]
    .codec[Double]
    .codec[Float]
    .codec[List[Int]]
    .codec[List[String]]
    .declare("sum") { (d: List[Int]) => d.sum }
    .declare("fac") { (n: Int) => (1 to n).foldLeft(1)(_ * _) }
    .declare("foo") { foo _ } // referencing existing functions works, too

  println(env)

  val addr = new InetSocketAddress("localhost", 8080)

  // create a server for this environment
  val server = Server.start(env)(addr)

  // on client - create local, typed declarations for server
  // functions you wish to call. This can be code generated
  // from `env`, since `env` has name/types for all declarations!
  import Remote.implicits._

  val fac = Remote.ref[Int => Int]("fac")
  val gcd = Remote.ref[(Int,Int) => Int]("gcd")
  val sum = Remote.ref[List[Int] => Int]("sum")

  // And actual client code uses normal looking function calls
  val ar = fac(9)
  val ar1 = gcd(1, 2)
  val ar3 = sum(List(0,1,2,3,4))
  val ar2: Remote[Int] = ar
  val r: Remote[Int] = ar3

  // to actually run a remote expression, we need an endpoint
  implicit val clientPool = akka.actor.ActorSystem("rpc-client")

  val loc: Endpoint = Endpoint.single(addr) // takes ActorSystem implicitly
  val result: Task[Int] = r.run(loc)

  try println { result.run }
  finally {
    // hack to allow asynchronous actor shutdown messages to propagate,
    // without this, we get some dead letter logging
    // I'm sure there's a better way to do this
    Thread.sleep(1000)
    server()
    clientPool.shutdown()
  }
}

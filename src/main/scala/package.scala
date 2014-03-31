
package object remotely {
  import scala.reflect.runtime.universe.TypeTag
  import scalaz.stream.{Bytes,Process}
  import scalaz.concurrent.Task
  import scalaz.Monoid
  import scodec.bits.{BitVector,ByteVector}
  import scodec.Decoder
  import remotely.server.Handler

  /**
   * Evaluate the given remote expression at the
   * specified endpoint, and get back the result.
   * This function is completely pure - no network
   * activity occurs until the returned `Task` is
   * run, which means that retry and/or circuit-breaking
   * logic can be added with a separate layer.
   */
  def evaluate[A:Decoder:TypeTag](e: Endpoint)(r: Remote[A]): Task[A] = for {
    conn <- e.get
    reqBits <- codecs.encodeRequest(r)
    respBytes <- {
      val reqBytestream = Process.emit(reqBits.toByteVector).pipe(Handler.frame)
      fullyRead(conn(reqBytestream).pipe(Handler.frames)) // we assume the server response is a framed stream
    }
    resp <- codecs.liftDecode(codecs.responseDecoder[A].decode(respBytes.toBitVector))
    result <- resp.fold(e => Task.fail(new Exception(e)),
                        a => Task.now(a))
  } yield result

  implicit val BitVectorMonoid = Monoid.instance[BitVector]((a,b) => a ++ b, BitVector.empty)
  implicit val ByteVectorMonoid = Monoid.instance[ByteVector]((a,b) => a ++ b, ByteVector.empty)

  private[remotely] def fullyRead(s: Process[Task,ByteVector]): Task[ByteVector] =
    s.runFoldMap(identity)
}

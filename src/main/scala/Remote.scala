package srpc

import scalaz.concurrent.Task
import scalaz.{\/, Monad}
import scala.reflect.ClassManifest
import scodec.{Encoder, codecs, Error}

/**
 * Represents a remote computation which yields a
 * value of type `A`.
 */
trait Remote[+A]

object Remote {

  /** Promote a local value to a remote value. */
  private[srpc] case class Local[A](
    a: A, // the value
    format: Encoder[A], // serializer for `A`
    tag: String // identifies the deserializer to be used by server
  ) extends Remote[A]

  /** Promote an asynchronous task to a remote value. */
  private[srpc] case class Async[A](
    a: Task[A],
    format: Encoder[A], // serializer for `A`
    tag: String // identifies the deserializer to be used by server
  ) extends Remote[A]

  /**
   * Reference to a remote value on the server.
   */
  private[srpc] case class Ref[A](name: String) extends Remote[A]

  // we require a separate constructor for each function
  // arity, since remote invocations must be fully saturated
  private[srpc] case class Ap1[A,B](
    f: Remote[A => B],
    a: Remote[A]) extends Remote[B]
  private[srpc] case class Ap2[A,B,C](
    f: Remote[(A,B) => C],
    a: Remote[A],
    b: Remote[B]) extends Remote[C]
  private[srpc] case class Ap3[A,B,C,D](
    f: Remote[(A,B,C) => D],
    a: Remote[A],
    b: Remote[B],
    c: Remote[C]) extends Remote[D]
  private[srpc] case class Ap4[A,B,C,D,E](
    f: Remote[(A,B,C,D) => E],
    a: Remote[A],
    b: Remote[B],
    c: Remote[C],
    d: Remote[D]) extends Remote[E]

  val T = Monad[Task]

  /**
   * Precursor to serializing a remote computation
   * to send to server for evaluation. This function
   * removes all `Async` constructors.
   */
  def localize[A](r: Remote[A]): Task[Remote[A]] = r match {
    case Async(a,c,t) => a.map { a => Local(a,c.asInstanceOf[Encoder[A]],t) }
    case Ap1(f,a) => T.apply2(localize(f), localize(a))(Ap1.apply)
    case Ap2(f,a,b) => T.apply3(localize(f), localize(a), localize(b))(Ap2.apply)
    case Ap3(f,a,b,c) => T.apply4(localize(f), localize(a), localize(b), localize(c))(Ap3.apply)
    case Ap4(f,a,b,c,d) => T.apply5(localize(f), localize(a), localize(b), localize(c), localize(d))(Ap4.apply)
    case _ => Task.now(r) // Ref or Local
  }

  // def serialize[A](r: Remote[A]): Process[Task,Bytes] =
}

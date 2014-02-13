package srpc

import scala.collection.immutable.SortedSet
import scala.reflect.runtime.universe.TypeTag
import scalaz.concurrent.Task
import scalaz.{\/, Applicative, Monad, Nondeterminism}
// import scala.reflect.runtime.universe.TypeTag
import scodec.{Codec,codecs => C,Decoder,Encoder,Error}
import scodec.codecs.Discriminator
import scodec.bits.BitVector
import shapeless._

/**
 * Represents a remote computation which yields a
 * value of type `A`.
 */
sealed trait Remote[+A]

object Remote {

  /** Promote a local value to a remote value. */
  private[srpc] case class Local[A](
    a: A, // the value
    format: Codec[A], // serializer for `A`
    tag: String // identifies the deserializer to be used by server
  ) extends Remote[A]
  implicit def localIso = Iso.hlist(Local.apply _, Local.unapply _)


  /** Promote an asynchronous task to a remote value. */
  private[srpc] case class Async[A](
    a: Task[A],
    format: Codec[A], // serializer for `A`
    tag: String // identifies the deserializer to be used by server
  ) extends Remote[A]
  implicit def asyncIso = Iso.hlist(Async.apply _, Async.unapply _)

  /**
   * Reference to a remote value on the server.
   */
  private[srpc] case class Ref[A](name: String) extends Remote[A]
  implicit def refIso = Iso.hlist(Ref.apply _, Ref.unapply _)

  // we require a separate constructor for each function
  // arity, since remote invocations must be fully saturated
  private[srpc] case class Ap1[A,B](
    f: Remote[A => B],
    a: Remote[A]) extends Remote[B]
  implicit def ap1Iso = Iso.hlist(Ap1.apply _, Ap1.unapply _)

  private[srpc] case class Ap2[A,B,C](
    f: Remote[(A,B) => C],
    a: Remote[A],
    b: Remote[B]) extends Remote[C]
  implicit def ap2Iso = Iso.hlist(Ap2.apply _, Ap2.unapply _)

  private[srpc] case class Ap3[A,B,C,D](
    f: Remote[(A,B,C) => D],
    a: Remote[A],
    b: Remote[B],
    c: Remote[C]) extends Remote[D]
  implicit def ap3Iso = Iso.hlist(Ap3.apply _, Ap3.unapply _)

  private[srpc] case class Ap4[A,B,C,D,E](
    f: Remote[(A,B,C,D) => E],
    a: Remote[A],
    b: Remote[B],
    c: Remote[C],
    d: Remote[D]) extends Remote[E]
  implicit def ap4Iso = Iso.hlist(Ap4.apply _, Ap4.unapply _)

  // private val T = Monad[Task] // the sequential Task monad

  /** This `Applicative[Task]` runs the tasks in parallel. */
  private val T = new Applicative[Task] {
    def point[A](a: => A) = Task.now(a)
    def ap[A,B](a: => Task[A])(f: => Task[A => B]): Task[B] = apply2(f,a)(_(_))
    override def apply2[A,B,C](a: => Task[A], b: => Task[B])(f: (A,B) => C): Task[C] =
      Nondeterminism[Task].mapBoth(a, b)(f)
  }

  /**
   * Precursor to serializing a remote computation
   * to send to server for evaluation. This function
   * removes all `Async` constructors.
   */
  def localize[A](r: Remote[A]): Task[Remote[A]] = r match {
    case Async(a,c,t) => a.map { a => Local(a,c.asInstanceOf[Codec[A]],t) }
    case Ap1(f,a) => T.apply2(localize(f), localize(a))(Ap1.apply)
    case Ap2(f,a,b) => T.apply3(localize(f), localize(a), localize(b))(Ap2.apply)
    case Ap3(f,a,b,c) => T.apply4(localize(f), localize(a), localize(b), localize(c))(Ap3.apply)
    case Ap4(f,a,b,c,d) => T.apply5(localize(f), localize(a), localize(b), localize(c), localize(d))(Ap4.apply)
    case _ => Task.now(r) // Ref or Local
  }

  /** Collect up all the `Ref` names referenced by `r`. */
  def refs[A](r: Remote[A]): SortedSet[String] = r match {
    case Local(a,e,t) => SortedSet.empty
    case Async(a,e,t) => sys.error(
      "cannot encode Async constructor; call Remote.localize first")
    case Ref(t) => SortedSet(t)
    case Ap1(f,a) => refs(f).union(refs(a))
    case Ap2(f,a,b) => refs(f).union(refs(b)).union(refs(b))
    case Ap3(f,a,b,c) => refs(f).union(refs(b)).union(refs(b)).union(refs(c))
    case Ap4(f,a,b,c,d) => refs(f).union(refs(b)).union(refs(b)).union(refs(c)).union(refs(d))
  }

  /** Collect up all the formats referenced by `r`. */
  def formats[A](r: Remote[A]): SortedSet[String] = r match {
    case Local(a,e,t) => SortedSet(t)
    case Async(a,e,t) => sys.error(
      "cannot encode Async constructor; call Remote.localize first")
    case Ref(t) => SortedSet.empty
    case Ap1(f,a) => formats(f).union(formats(a))
    case Ap2(f,a,b) => formats(f).union(formats(b)).union(formats(b))
    case Ap3(f,a,b,c) => formats(f).union(formats(b)).union(formats(b)).union(formats(c))
    case Ap4(f,a,b,c,d) => formats(f).union(formats(b)).union(formats(b)).union(formats(c)).union(formats(d))
  }

  def toTag[A](implicit A: TypeTag[A]): String =
    A.tpe.toString

  def nameToTag[A](s: String)(implicit A: TypeTag[A]): String =
    s"$s: ${toTag[A]}"

}

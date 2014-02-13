package srpc

import scala.collection.immutable.{IndexedSeq,Set,SortedMap,SortedSet}
import scala.math.Ordering
import scalaz.{\/,Monad}
import scalaz.concurrent.Task
import scodec.{Codec,codecs => C,Decoder,Encoder,Error}
import scodec.bits.BitVector
import Remote._

object Codecs {

  implicit val int32 = C.int32
  implicit val int64 = C.int64
  implicit val utf8 = C.utf8
  implicit val bool = C.bool

  implicit def tuple2[A:Codec,B:Codec]: Codec[(A,B)] =
    Codec[A] ~ Codec[B]

  implicit def byteArray: Codec[Array[Byte]] = ???

  implicit def seq[A:Codec]: Codec[Seq[A]] =
    C.repeated(Codec[A]).xmap[Seq[A]](
      a => a,
      _.toIndexedSeq
    )
  implicit def sortedSet[A:Codec:Ordering]: Codec[SortedSet[A]] =
    indexedSeq[A].xmap[SortedSet[A]](
      s => SortedSet(s: _*),
      _.toIndexedSeq)

  implicit def set[A:Codec]: Codec[Set[A]] =
    indexedSeq[A].xmap[Set[A]](
      s => Set(s: _*),
      _.toIndexedSeq)

  implicit def indexedSeq[A:Codec]: Codec[IndexedSeq[A]] =
    C.repeated(Codec[A])

  implicit def list[A:Codec]: Codec[List[A]] =
    indexedSeq[A].xmap[List[A]](
      _.toList,
      _.toIndexedSeq)

  implicit def map[K:Codec,V:Codec]: Codec[Map[K,V]] =
    indexedSeq[(K,V)].xmap[Map[K,V]](
      _.toMap,
      _.toIndexedSeq
    )

  implicit def sortedMap[K:Codec:Ordering,V:Codec]: Codec[SortedMap[K,V]] =
    indexedSeq[(K,V)].xmap[SortedMap[K,V]](
      kvs => SortedMap(kvs: _*),
      _.toIndexedSeq
    )

  import scalaz.\/._
  implicit class PlusSyntax(e: Error \/ BitVector) {
    def <+>(r: => Error \/ BitVector): Error \/ BitVector =
      e.flatMap(bv => r.map(bv ++ _))
  }

  def remoteEncode[A](r: Remote[A]): Error \/ BitVector =
    r match {
      case Local(a,e,t) => C.uint8.encode(0) <+> // tag byte
        C.utf8.encode(t) <+> e.asInstanceOf[Codec[A]].encode(a)
      case Async(a,e,t) =>
        left("cannot encode Async constructor; call Remote.localize first")
      case Ref(t) => C.uint8.encode(1) <+>
        C.utf8.encode(t)
      case Ap1(f,a) => C.uint8.encode(2) <+>
        remoteEncode(f) <+> remoteEncode(a)
      case Ap2(f,a,b) => C.uint8.encode(3) <+>
        remoteEncode(f) <+> remoteEncode(a) <+> remoteEncode(b)
      case Ap3(f,a,b,c) => C.uint8.encode(4) <+>
        remoteEncode(f) <+> remoteEncode(a) <+> remoteEncode(b) <+> remoteEncode(c)
      case Ap4(f,a,b,c,d) => C.uint8.encode(5) <+>
        remoteEncode(f) <+> remoteEncode(a) <+> remoteEncode(b) <+> remoteEncode(c) <+> remoteEncode(d)
    }

  private val E = Monad[Decoder]

  /**
   * A `Remote[Any]` decoder. If a `Local` value refers
   * to a decoder that is not found in `env`, decoding fails
   * with an error.
   */
  def remoteDecoder(env: Map[String,Codec[Any]]): Decoder[Remote[Any]] = {
    lazy val go = remoteDecoder(env)
    C.uint8.flatMap {
      case 0 => C.utf8.flatMap { fmt =>
                  env.get(fmt) match {
                    case None => fail(s"[decoding] unknown format type: $fmt")
                    case Some(codec) => codec.map { a => Local(a,codec,fmt) }
                  }
                }
      case 1 => C.utf8.map(Ref.apply)
      case 2 => E.apply2(go,go)((f,a) =>
                  Ap1(f.asInstanceOf[Remote[Any => Any]],a))
      case 3 => E.apply3(go,go,go)((f,a,b) =>
                  Ap2(f.asInstanceOf[Remote[(Any,Any) => Any]],a,b))
      case 4 => E.apply4(go,go,go,go)((f,a,b,c) =>
                  Ap3(f.asInstanceOf[Remote[(Any,Any,Any) => Any]],a,b,c))
      case 5 => E.apply5(go,go,go,go,go)((f,a,b,c,d) =>
                  Ap4(f.asInstanceOf[Remote[(Any,Any,Any,Any) => Any]],a,b,c,d))
      case t => fail(s"[decoding] unknown tag byte: $t")
    }
  }

  implicit def remoteEncoder[A]: Encoder[Remote[A]] =
    new Encoder[Remote[A]] { def encode(a: Remote[A]) = remoteEncode(a) }

  /**
   * Wait for all `Async` tasks to complete, then encode
   * the remaining concrete expression. The produced
   * bit vector may be read by `remoteDecoder`. That is,
   * `encodeRequest(r).flatMap(bits => decodeRequest(env).decode(bits))`
   * should succeed, given a suitable `env` which knows how
   * to decode the serialized values.
   *
   * Use `encode(r).map(_.toByteArray)` to produce a `Task[Array[Byte]]`.
   */
  def encodeRequest[A](r: Remote[A]): Task[BitVector] =
    localize(r).flatMap { a =>
      (sortedSet[String].encode(formats(a)) <+>
       remoteEncode(a)).fold(
         err => Task.fail(new EncodingFailure(err)),
         bits => Task.now(bits)
       )
    }

  def requestDecoder(env: Map[String,Codec[Any]]): Decoder[Remote[Any]] =
    sortedSet[String].flatMap { ks =>
      val unknown = (ks -- env.keySet).toList
      if (unknown.isEmpty) remoteDecoder(env)
      else fail(s"[decoding] server does not have deserializers for: $unknown")
    }

  def fail(msg: String): Decoder[Nothing] =
    new Decoder[Nothing] { def decode(bits: BitVector) = left(msg) }

  def succeed[A](a: A): Decoder[A] = C.provide(a)

  class EncodingFailure(msg: String) extends Exception(msg)
  class DecodingFailure(msg: String) extends Exception(msg)

}

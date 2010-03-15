// -----------------------------------------------------------------------------
//
//  scala.arm - The Scala Incubator Project
//  Copyright (c) 2009 The Scala Incubator Project. All rights reserved.
//
//  The primary distribution site is http://jsuereth.github.com/scala-arm
//
//  This software is released under the terms of the Revised BSD License.
//  There is NO WARRANTY.  See the file LICENSE for the full text.
//
// -----------------------------------------------------------------------------


package scala.resource

import _root_.scala.collection.Traversable
import _root_.scala.collection.Iterator
import _root_.scala.util.control.Exception
import _root_.scala.Either
import util.continuations.{cpsParam, cps}

/**
 * This class encapsulates a method of ensuring a resource is opened/closed during critical stages of its lifecycle.
 */
trait ManagedResource[+R] {

  /**
   * This method is used to perform operations on a resource while the resource is open.
   */
  def map[B, To](f : R => B)(implicit translator : CanSafelyTranslate[B,To]) : To
  //def map[B](f : R => B) : ExtractableManagedResource[B]

  /**
   * This method is used to immediately perform operations on a resource while it is open, ensuring the resource is
   * closed before returning.
   */
  //def flatMap[B](f : R => ManagedResource[B]) : ManagedResource[B]    
  def flatMap[B, To](f : R => B)(implicit translator : CanSafelyTranslate[B,To]) : To

  /**
   * This method is used to immediately perform operations on a resource while it is open, ensuring the resource is
   * closed before returning.
   */
  def foreach(f : R => Unit) : Unit

  /**
   * Aquires the resource for the Duration of a given function, closing when complete and returning the result.
   *
   * This method will throw the last exception encountered by the managed resource.
   *
   * @param f   A function to execute against the handle returned by the resource
   * @return    The result of the passed in function
   */
  def acquireAndGet[B](f : R => B) : B

  /**
   * Aquires the resource for the Duration of a given function, closing when complete and returning the result.
   *
   * @param f   A function to execute against the handle returned by the resource
   * @return    The result of the function (right) or the list of exceptions seen during the processing of the
   *            resource (left).
   */
  def acquireFor[B](f : R => B) : Either[List[Throwable], B]

  /**
   * This method creates a Traversable in which all performed methods are done withing the context of an "open"
   * resource
   */
  def toTraversable[B](f : R => Iterator[B]) : Traversable[B]

  /**
   * Creates a new resource that is the aggregation of this resource and another.
   *
   * @param that
   *          The other resource
   */
  def and[B](that : ManagedResource[B]) : ManagedResource[(R,B)]

  /**
   * Reflects the resource for use in a continuation.
   */
  def reflect[B] :  R @cpsParam[B,Either[List[Throwable], B]]


  /**
   * Reflects the resource for use in a continuation.  Exceptions will not be stored in an Either, but thrown after
   * resource management is complete
   */
  def reflectUnsafe[B] : R @cps[B]
}

trait LowPriorityManagedResourceImplicits {
  /** This translate method converts from ManagedResource to ExtractableManagedResource, keeping the processed result inside the managed monad. */
  implicit def stayManaged[B] = new CanSafelyTranslate[B, ExtractableManagedResource[B]] {
    def apply[A](from : ManagedResource[A],  converter : A => B) : ExtractableManagedResource[B] =  new DeferredExtractableManagedResource(from, converter)
  }
}

trait HighPriorityManagedResourceImplicits extends LowPriorityManagedResourceImplicits {

  /** Assumes any mapping function to an iterator type creates a "traversable" */
  implicit def convertToTraversable[A, CC[A] <: Traversable[A]] = new CanSafelyTranslate[CC[A], Traversable[A]] {
     override def apply[T](from : ManagedResource[T],  converter : T => CC[A]) : Traversable[A] = new ManagedTraversable[A,Traversable[A]] {
        override val resource = from.map(converter)
        override protected def internalForeach[U](resource: Traversable[A], f : A => U) : Unit = resource.foreach(f)
     }
  }

  /** Assumes any mapping can remain in monad */
  implicit def stayManagedFlatten[B] = new CanSafelyTranslate[ManagedResource[B], ManagedResource[B]] {
      def apply[A](from : ManagedResource[A],  converter : A => ManagedResource[B]) : ManagedResource[B] = new ManagedResourceOperations[B] {
          override def acquireFor[C](f2 : B => C) : Either[List[Throwable], C] = {
            from.acquireFor( r => converter(r).acquireFor(f2)).fold(x => Left(x), x => x)
          }
      }
  }
}

/**
 * TODO - Place real usage stuff here!
 */
object ManagedResource extends HighPriorityManagedResourceImplicits {

  /** 
   * This method can be used to extract from a ManagedResource some value while mapping/flatMapping.
   *  e.g. <pre> val x : ManagedResource[Foo]
   *   val someValue = x.flatMap( foo : Foo => foo.getSomeValue )(extractUnManaged)
   * </pre>
   */
  def extractUnManaged[T] = new CanSafelyTranslate[T, T] {
     def apply[A](from : ManagedResource[A], converter : A => T) : T = from.acquireAndGet(converter)     
  }
  /** 
   * This method can be used to extract from a ManagedResource some value while mapping/flatMapping.
   *  e.g. <pre> val x : ManagedResource[Foo]
   *   val someValue : Option[SomeValue] = x.flatMap( foo : Foo => foo.getSomeValue )(extractOption)
   * </pre>
   */
  def extractOption[T] = new CanSafelyTranslate[T, Option[T]] {
     def apply[A](from : ManagedResource[A], converter : A => T) : Option[T] = (new DeferredExtractableManagedResource(from, converter)).opt
  }
	/**
	 * Creates a ManagedResource for any type with a close method. Note that the opener argument is evaluated on demand,
	 * possibly more than once, so it must contain the code that actually acquires the resource. Clients are encouraged
	 * to write specialized methods to instantiate ManagedResources rather than relying on ad-hoc usage of this method.
	 */
	def apply[A <: { def close() : Unit }](opener : => A) : ManagedResource[A] =
    new AbstractUntranslatedManagedResource[A] {
      override protected def open = opener
			override def unsafeClose(r : A) = r.close()
	  }

  /**
   * Takes a sequence of ManagedResource objects and traits them as a ManagedResource of a Sequence of Objects.
   *
   * This is useful for dealing with many resources within the same scope.
   *
   * @param resource   A collection of ManageResources of the same type
   * @return  A ManagedResoruce of a collection of types
   */
  def join[A, MR <: ManagedResource[A], CC <: Seq[ MR ] ](resources : CC) : ManagedResource[Seq[A]] = {
    //TODO - Use foldLeft
    //TODO - Don't use such a sucky algorithm...
    //We currently assume 1 resource
    //TODO - See if we can provide Hlist implementation as well...
    val itr = (resources.reverseIterator : Iterator[ManagedResource[A]])
    val first : ManagedResource[A] = itr.next
    var toReturn : ManagedResource[Seq[A]] = first.map( x => Seq(x))
    while(itr.hasNext) {
      val r1 = toReturn
      val r2 : ManagedResource[A] = itr.next
      toReturn = new ManagedResource[Seq[A]] with ManagedResourceOperations[Seq[A]] {
        override def acquireFor[B](f : Seq[A] => B) : Either[List[Throwable], B] = r1.acquireFor {
            r1seq =>
               r2.acquireAndGet { r2item =>
                  f( r2item :: r1seq.toList)
               }
        }
      }
    }
    toReturn
  }

}





package io.getquill

import scala.language.implicitConversions
import io.getquill.quoter.QuotationLot
import io.getquill.quoter.Dsl._
import io.getquill._
import io.getquill.ast._
import io.getquill.quoter.Quoted
import io.getquill.quoter.Planter
import io.getquill.quoter.EagerPlanter
import io.getquill.quoter.LazyPlanter
import io.getquill.quoter.QuotationVase
import io.getquill.quoter.QuotationLot
import org.scalatest._

class QuotationTest extends Spec with Inside {
  case class Address(street:String, zip:Int) extends Embedded
  case class Person(name: String, age: Int, address: Address)

  

  ("compiletime quotation has correct ast for") - {
    "trivial whole-record select" in {
      inline def q = quote {
        query[Person]
      }
      q.ast mustEqual Entity("Person", List())
    }
    "single field mapping" in {
      inline def q = quote {
        query[Person].map(p => p.name) // also try _.name
      }
      q.ast mustEqual Map(Entity("Person", List()), Ident("p"), Property(Ident("p"), "name"))
    }
    "anonymous single field mapping" in {
      inline def q = quote {
        query[Person].map(_.name)
      }
      q.ast mustEqual Map(Entity("Person", List()), Ident("x1"), Property(Ident("x1"), "name"))
    }
    "unquoted splice into another quotation" in {
      inline def q = quote {
        query[Person] // also try _.name
      }
      inline def qq = quote {
        q.map(p => p.name)
      }
       qq.ast mustEqual Map(Entity("Person", List()), Ident("p"), Property(Ident("p"), "name"))
    }
    "double unquoted splice into another quotation" in {
      inline def q = quote {
        query[Person] // also try _.name
      }
      inline def qq = quote {
        q.map(p => p.name)
      }
      inline def qqq = quote {
        qq.map(s => s)
      }
       qq.ast mustEqual Map(Entity("Person", List()), Ident("p"), Property(Ident("p"), "name"))
    }
    "double unquoted splict with a lift" in {
      inline def q = quote {
        query[Person] // also try _.name
      }
      inline def qq = quote {
        q.map(p => p.name)
      }
      // We only need a context to do lifts
      val ctx = new MirrorContext(MirrorSqlDialect, Literal)
      import ctx._
      inline def qqq = quote {
        qq.map(s => s + lift("hello")) // TODO Need to add tests with lift to QueryTest
      }
       qq.ast mustEqual Map(Entity("Person", List()), Ident("p"), Property(Ident("p"), "name"))
    }
  }

  "runtime quotation has correct ast for" - {
    "simple one-level query with map" in {
      val q = quote {
        query[Person].map(p => p.name) // also try _.name
      }
       q.ast mustEqual Map(Entity("Person", List()), Ident("p"), Property(Ident("p"), "name"))
    }
    "two-level query with map" in {
      val q = quote {
        query[Person]
      }
      val qq = quote {
        q.map(p => p.name)
      }
      inside(qq) {
        case Quoted(
          Map(QuotationTag(tagId), Ident("p"), Property(Ident("p"), "name")),
          List(),
          List(QuotationVase(Quoted(Entity("Person", List()), List(), List()), vaseId))
        ) if (vaseId == tagId) =>
      }
    }
    "query with a lift" in {
      import io.getquill.context.mirror.Row
      val ctx = new MirrorContext(MirrorSqlDialect, Literal)
      import ctx._
      inline def q = quote {
        lift("hello")
      }
      inside(q) {
        case Quoted(ScalarTag(tagUid), List(EagerPlanter("hello", encoder, vaseUid)), List()) if (tagUid == vaseUid) =>
      }
      val vase = 
        q.lifts match {
          case head :: Nil => head.asInstanceOf[EagerPlanter[String, ctx.PrepareRow /* or just Row */]]
        }
        
      Row("hello") mustEqual vase.encoder.apply(0, vase.value, new Row())
    }
    "two-level query with a lift" in {
      import io.getquill.context.mirror.Row
      val ctx = new MirrorContext(MirrorSqlDialect, Literal)
      import ctx._
      val q = quote {
        lift("hello")
      }
      val qq = quote {
        q
      }
      inside(qq) {
        case 
          Quoted(
            QuotationTag(quotationTagId),
            List(),
            List(
              QuotationVase(
                Quoted(
                  ScalarTag(scalarTagId),
                  List(EagerPlanter("hello", _, planterId)),
                  List()
                ),
                quotationVaseId
              )
            )
          ) if (quotationTagId == quotationVaseId && scalarTagId == planterId) =>
      }
      val vase = 
        q.lifts match {
          case head :: Nil => head.asInstanceOf[EagerPlanter[String, ctx.PrepareRow /* or just Row */]]
        }
        
      Row("hello") mustEqual vase.encoder.apply(0, vase.value, new Row())
    }
    "query with a lift and plus operator" in {
      val ctx = new MirrorContext(MirrorSqlDialect, Literal)
      import ctx._
  
      inline def q = quote {
        query[Person].map(p => p.name + lift("hello"))
      }
      inside(q) {
        case Quoted(
            Map(Entity("Person", List()), Ident("p"), BinaryOperation(Property(Ident("p"), "name"), StringOperator.+, ScalarTag(tagUid))),
            List(EagerPlanter("hello", _, planterUid)), // TODO Test what kind of encoder it is? Or try to run it and make sure it works?
            List()
          ) if (tagUid == planterUid) => true
        case _ => false
      }
    }
    "two level query with a lift and plus operator" in {
      case class Address(street:String, zip:Int) extends Embedded
      case class Person(name: String, age: Int, address: Address)
    
      inline def q = quote {
        query[Person] // also try _.name
      }
      inline def qq = quote {
        q.map(p => p.name)
      }
      // We only need a context to do lifts
      val ctx = new MirrorContext(PostgresDialect, Literal) //hello
      import ctx._
      inline def qqq = quote {
        qq.map(s => s + lift("hello"))
      }
      printer.lnf(qqq)
      qq.ast mustEqual Map(Entity("Person", List()), Ident("p"), Property(Ident("p"), "name"))
    }
  }
}


// @main def simpleLift = {
//   import io.getquill.context.mirror.Row

//   case class Person(name: String)

//   val ctx = new MirrorContext(MirrorSqlDialect, Literal)
//   import ctx._
//   inline def q = quote {
//     liftEager("hello")
//   }
//   println(q)
//   val vase = q.lifts.asInstanceOf[Product].productIterator.toList.head.asInstanceOf[Planter[String, ctx.PrepareRow /* or just Row */]]
//   println(vase.encoder.apply(0, vase.value, new Row()))
// }

// @main def runtimeEagerLift = {
//   import io.getquill.context.mirror.Row

//   case class Person(name: String)

//   val ctx = new MirrorContext(MirrorSqlDialect, Literal)
//   import ctx._
//   def q = quote {
//     liftEager("hello")
//   }
//   val qq = quote { q }
//   println(qq)
//   val vase = q.lifts.asInstanceOf[Product].productIterator.toList.head.asInstanceOf[Planter[String, ctx.PrepareRow /* or just Row */]]
//   println(vase.encoder.apply(0, vase.value, new Row()))
// }


// @main def liftAndRun = {
//   import io.getquill.context.mirror.Row

//   case class Person(name: String)

//   inline def q = quote {
//     query[Person].map(p => p.name + lift("foo"))
//   }

//   val ctx = new MirrorContext(MirrorSqlDialect, Literal)
//   import ctx._

//   println(q)

//   println(run(q).string)
//   println(run(q).prepareRow(new Row()))
// }

// @main def identTest = {
//   //import scala.language.implicitConversions

//   val ctx = new MirrorContext(MirrorSqlDialect, Literal)
//   import ctx._
//   val q = quote {
//     lift("hello")
//   }
//   val qq = quote { //hello
//     q
//   }
//   printer.lnf(qq)
//   //val output = run(qq)
// }

// TODO Need to have an error for this scenario!!
// @main def noEncoderTestRuntime = { 
//   //import scala.language.implicitConversions
//   case class Person(name: String, age: Int)

//   class Foo
//   val q = quote {
//     query[Person].map(p => p.name + lift(new Foo))
//   }
//   val ctx = new MirrorContext(MirrorSqlDialect, Literal)
//   import ctx._
//   val qq = quote { //hello
//     q
//   }
//   printer.lnf(qq)
//   val output = run(qq)
// }

// TODO This is a negative test (i.e. encoder finding should not work. Should look into how to write these for dotty)
// @main def noEncoderTestCompile = { 
//   case class Person(name: String, age: Int)

//   class Foo
//   val ctx = new MirrorContext(MirrorSqlDialect, Literal)
//   import ctx._
//   inline def q = quote {
//     query[Person].map(p => p.name + lift(new Foo))
//   }
  
//   inline def qq = quote { //hello
//     q
//   }
//   printer.lnf(qq)
//   val output = run(qq)
// }

// test a runtime quotation
// test two runtime quotations
// test a runtime going into a compiletime


// test a quotation with a context
// test a quotation producing a variable
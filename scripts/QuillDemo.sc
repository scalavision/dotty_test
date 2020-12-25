import $ivy.`io.getquill::quill-sql:3.5.2`

import io.getquill._

case class Person(name: String, age: Int)

object Test {

  val ctx = new SqlMirrorContext[PostgresDialect, Literal](PostgresDialect, Literal)

  import ctx._


  val q = quote { query[Person] }

  val sql = run(q)

  import scala.reflect.runtime.universe._

  def classNameOf[T](implicit typeTag: TypeTag[T]): String = typeTag.tpe.typeSymbol.fullName
  def classNameOf2[T: TypeTag]: String = implicitly[TypeTag[T]].tpe.typeSymbol.fullName

}

pprint.pprintln(Test.q)
pprint.pprintln(Test.sql)
pprint.pprintln(Test.classNameOf[Person])
pprint.pprintln(Test.classNameOf[java.util.Date])
pprint.pprintln(Test.classNameOf2[java.util.Date])


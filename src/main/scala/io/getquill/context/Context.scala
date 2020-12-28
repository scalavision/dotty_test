package io.getquill.context

import scala.language.higherKinds
import scala.language.experimental.macros
import java.io.Closeable
import scala.compiletime.summonFrom
import scala.util.Try
import io.getquill.{ ReturnAction }
import io.getquill.dsl.EncodingDsl
import io.getquill.quoter.Quoted
import io.getquill.quoter.QueryMeta
import io.getquill.derived._
import io.getquill.context.mirror.MirrorDecoders
import io.getquill.context.mirror.Row
import io.getquill.dsl.GenericDecoder
import io.getquill.quoter.Planter
import io.getquill.ast.Ast
import io.getquill.ast.ScalarTag
import scala.quoted._
import io.getquill.idiom.Idiom
import io.getquill.ast.{Transform, QuotationTag}
import io.getquill.quoter.QuotationLot
import io.getquill.quoter.QuotedExpr
import io.getquill.quoter.PlanterExpr
import io.getquill.idiom.ReifyStatement
import io.getquill.Query
import QueryExecution.QuotedOperation

import io.getquill._

sealed trait ExecutionType
object ExecutionType {
  case object Dynamic extends ExecutionType
  case object Static extends ExecutionType
}

trait ProtoContext[Dialect <: io.getquill.idiom.Idiom, Naming <: io.getquill.NamingStrategy] {
  type PrepareRow
  type ResultRow

  type Result[T]
  type RunQuerySingleResult[T]
  type RunQueryResult[T]
  type RunActionResult
  type RunActionReturningResult[T]
  type RunBatchActionResult
  type RunBatchActionReturningResult[T]
  type Session
  /** Future class to hold things like ExecutionContext for Cassandra etc... */
  type DatasourceContext

  type Prepare = PrepareRow => (List[Any], PrepareRow)
  type Extractor[T] = ResultRow => T

  case class BatchGroup(string: String, prepare: List[Prepare])
  case class BatchGroupReturning(string: String, returningBehavior: ReturnAction, prepare: List[Prepare])

  def idiom: Dialect
  def naming: Naming
  
  val identityPrepare: Prepare = (Nil, _)
  val identityExtractor = identity[ResultRow] _

  def executeQuery[T](sql: String, prepare: Prepare, extractor: Extractor[T])(executionType: ExecutionType, dc: DatasourceContext): Result[RunQueryResult[T]]
  def executeAction[T](sql: String, prepare: Prepare = identityPrepare)(executionType: ExecutionType, dc: DatasourceContext): Result[RunActionResult]

  // Cannot implement 'run' here because it's parameter needs to be inline, and we can't override a non-inline parameter with an inline one
}

// TODO Needs to be portable (i.e. plug into current contexts when compiled with Scala 3)
trait Context[Dialect <: io.getquill.idiom.Idiom, Naming <: io.getquill.NamingStrategy]
extends ProtoContext[Dialect, Naming]
with EncodingDsl //  with Closeable
{ self =>

  implicit inline def autoDecoder[T]:Decoder[T] = GenericDecoder.derived

  //def probe(statement: String): Try[_]
  // todo add 'prepare' i.e. encoders here
  //def executeAction[T](cql: String, prepare: Prepare = identityPrepare)(implicit executionContext: ExecutionContext): Result[RunActionResult]

  inline def lift[T](inline vv: T): T = 
    ${ LiftMacro[T, PrepareRow]('vv) }

  inline def runQueryBase[T](inline quoted: Quoted[Query[T]], inline dc: DatasourceContext): Result[RunQueryResult[T]] = {
    val ca = new ContextOperation[T, Dialect, Naming, PrepareRow, ResultRow, Result[RunQueryResult[T]]](self.idiom, self.naming) {
      def execute(sql: String, prepare: PrepareRow => (List[Any], PrepareRow), extractorOpt: Option[ResultRow => T], executionType: ExecutionType) =
        val extractor = 
          extractorOpt match
            case Some(e) => e
            case None => throw new IllegalArgumentException("Extractor required")

        self.executeQuery(sql, prepare, extractor)(executionType, dc)
    }
    // TODO Could make Quoted operation constructor that is a typeclass, not really necessary though
    QueryExecution.apply(QuotedOperation.QueryOp(quoted), ca)
  }

  inline def runActionBase[T](inline quoted: Quoted[Action[T]], inline dc: DatasourceContext): Result[RunActionResult] = {
    val ca = new ContextOperation[T, Dialect, Naming, PrepareRow, ResultRow, Result[RunActionResult]](self.idiom, self.naming) {
      def execute(sql: String, prepare: PrepareRow => (List[Any], PrepareRow), extractorOpt: Option[ResultRow => T], executionType: ExecutionType) =
        self.executeAction(sql, prepare)(executionType, dc)
    }
    QueryExecution.apply(QuotedOperation.ActionOp(quoted), ca)
  }


}

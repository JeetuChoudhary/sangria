package sangria.execution

import sangria.marshalling.InputUnmarshaller

import language.{implicitConversions, existentials}

import sangria.ast
import sangria.schema.{Schema, Action, Context}

import scala.annotation.unchecked.uncheckedVariance


trait Middleware[-Ctx] {
  type QueryVal

  def beforeQuery(context: MiddlewareQueryContext[Ctx, _, _]): QueryVal
  def afterQuery(queryVal: QueryVal, context: MiddlewareQueryContext[Ctx, _, _]): Unit
}

trait MiddlewareBeforeField[Ctx] extends Middleware[Ctx] {
  type FieldVal

  def beforeField(queryVal: QueryVal, mctx: MiddlewareQueryContext[Ctx, _, _], ctx: Context[Ctx, _]): (FieldVal, Option[Action[Ctx, _]])

  lazy val continue: (Unit, Option[Action[Ctx, _]]) = (Unit, None)
  def continue(fieldVal: FieldVal): (FieldVal, Option[Action[Ctx, _]]) = (fieldVal, None)
}

trait MiddlewareAfterField[Ctx] extends MiddlewareBeforeField[Ctx] {
  def afterField(queryVal: QueryVal, fieldVal: FieldVal, value: Any, mctx: MiddlewareQueryContext[Ctx, _, _], ctx: Context[Ctx, _]): Option[Any]
}

trait MiddlewareErrorField[Ctx] extends MiddlewareBeforeField[Ctx] {
  def fieldError(queryVal: QueryVal, fieldVal: FieldVal, error: Throwable, mctx: MiddlewareQueryContext[Ctx, _, _], ctx: Context[Ctx, _]): Unit
}

case class MiddlewareQueryContext[+Ctx, RootVal, Input](
  ctx: Ctx,
  executor: Executor[Ctx, RootVal, Schema[Ctx, RootVal]] @uncheckedVariance,
  queryAst: ast.Document,
  operationName: Option[String],
  variables: Input,
  inputUnmarshaller: InputUnmarshaller[Input])

trait FieldTag

case class StringTag(name: String)

object FieldTag {
  implicit def stringTag(s: String): StringTag = StringTag(s)
  implicit def symbolTag(s: Symbol): StringTag = StringTag(s.name)
}



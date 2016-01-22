package sangria.schema

import sangria.execution.FieldTag
import sangria.marshalling.FromInput.{InputObjectResult, CoercedScalaResult}
import sangria.marshalling.{FromInput, ToInput}

import language.{implicitConversions, existentials}

import sangria.{introspection, ast}
import sangria.validation.{EnumValueCoercionViolation, EnumCoercionViolation, Violation}
import sangria.introspection.{SchemaMetaField, TypeMetaField, TypeNameMetaField}

import scala.annotation.implicitNotFound
import scala.reflect.ClassTag

import sangria.util.tag
import sangria.util.tag._

sealed trait Type

sealed trait InputType[+T] extends Type

sealed trait OutputType[+T] extends Type

sealed trait LeafType extends Type
sealed trait CompositeType[T] extends Type with Named with OutputType[T]
sealed trait AbstractType extends Type with Named {
  def name: String

  def typeOf[Ctx](value: Any, schema: Schema[Ctx, _]): Option[ObjectType[Ctx, _]] =
    schema.possibleTypes get name flatMap (_.find(_ isInstanceOf value).asInstanceOf[Option[ObjectType[Ctx, _]]])
}

sealed trait NullableType
sealed trait UnmodifiedType

sealed trait Named {
  def name: String
  def description: Option[String]
}

object Named {
  private val NameRegexp = """^[_a-zA-Z][_a-zA-Z0-9]*$""".r

  private[schema] def doCheckNonEmptyFields(fields: Seq[Named]): Unit =
    if (fields.isEmpty)
      throw new IllegalArgumentException("No fields provided! You need to provide at least one field to a Type.")

  private[schema] def doCheckUniqueFields(fields: Seq[Named]): Unit =
    if (fields.map(_.name).toSet.size != fields.size)
      throw new IllegalArgumentException("All fields within a Type should have unique names!")

  private[schema] def doCheckFieldNames(fields: Seq[Named]): Unit =
    fields.foreach(f ⇒ checkName(f.name))

  private[schema] def checkObjFields[T <: Seq[Named]](fields: T): T = {
    doCheckUniqueFields(fields)
    doCheckFieldNames(fields)
    fields
  }

  private[schema] def checkIntFields[T <: Seq[Named]](fields: T): T = {
    doCheckNonEmptyFields(fields)
    doCheckUniqueFields(fields)
    doCheckFieldNames(fields)
    fields
  }

  private[schema] def checkObjFieldsFn[T <: Seq[Named]](fields: T): () ⇒ T = {
    doCheckUniqueFields(fields)
    doCheckFieldNames(fields)
    () ⇒ fields
  }

  private[schema] def checkIntFieldsFn[T <: Seq[Named]](fields: T): () ⇒ T = {
    doCheckUniqueFields(fields)
    doCheckNonEmptyFields(fields)
    doCheckFieldNames(fields)
    () ⇒ fields
  }

  private[schema] def checkObjFields[T <: Seq[Named]](fieldsFn: () ⇒ T): () ⇒ T =
    () ⇒ checkObjFields(fieldsFn())

  private[schema] def checkIntFields[T <: Seq[Named]](fieldsFn: () ⇒ T): () ⇒ T =
    () ⇒ checkIntFields(fieldsFn())

  private[schema] def checkName(name: String) = {
    if (!NameRegexp.pattern.matcher(name).matches())
      throw new IllegalArgumentException(s"Name '$name' is not valid GraphQL name! Valid name should satisfy following regex: /$NameRegexp/.")

    name
  }

}

case class ScalarType[T](
  name: String,
  description: Option[String] = None,
  coerceUserInput: Any ⇒ Either[Violation, T],
  coerceOutput: T ⇒ ast.Value,
  coerceInput: ast.Value ⇒ Either[Violation, T],
  complexity: Double = 0.0D) extends InputType[T @@ CoercedScalaResult] with OutputType[T] with LeafType with NullableType with UnmodifiedType with Named

sealed trait ObjectLikeType[Ctx, Val] extends OutputType[Val] with CompositeType[Val] with NullableType with UnmodifiedType with Named {
  def interfaces: List[InterfaceType[Ctx, _]]

  def fieldsFn: () ⇒ List[Field[Ctx, Val]]

  lazy val ownFields = fieldsFn().toVector

  def removeDuplicates[T, E](list: Vector[T], valueFn: T ⇒ E) =
    list.foldLeft((Vector.empty, Vector.empty): (Vector[E], Vector[T])) {
      case (a @ (visited, acc), e) if visited contains valueFn(e) ⇒ a
      case ((visited, acc), e) ⇒ (visited :+ valueFn(e), acc :+ e)
    }._2

  lazy val fields: Vector[Field[Ctx, _]] = ownFields ++ interfaces.flatMap(i ⇒ i.fields.asInstanceOf[Vector[Field[Ctx, _]]])

  lazy val uniqueFields: Vector[Field[Ctx, _]] = removeDuplicates(fields, (e: Field[Ctx, _]) ⇒ e.name)

  private lazy val fieldsByName = fields groupBy (_.name)

  def getField(schema: Schema[_, _], fieldName: String): Vector[Field[Ctx, _]] =
    if (sangria.introspection.MetaFieldNames contains fieldName)
      if (fieldName == SchemaMetaField.name && name == schema.query.name) Vector(SchemaMetaField.asInstanceOf[Field[Ctx, _]])
      else if (fieldName == TypeMetaField.name && name == schema.query.name) Vector(TypeMetaField.asInstanceOf[Field[Ctx, _]])
      else if (fieldName == TypeNameMetaField.name) Vector(TypeNameMetaField.asInstanceOf[Field[Ctx, _]])
      else Vector.empty
    else fieldsByName.getOrElse(fieldName, Vector.empty)
}

case class ObjectType[Ctx, Val: ClassTag] private (
  name: String,
  description: Option[String],
  fieldsFn: () ⇒ List[Field[Ctx, Val]],
  interfaces: List[InterfaceType[Ctx, _]]
) extends ObjectLikeType[Ctx, Val] {

  def isInstanceOf(value: Any) = implicitly[ClassTag[Val]].runtimeClass.isAssignableFrom(value.getClass)
}

object ObjectType {
  def apply[Ctx, Val: ClassTag](name: String, fields: List[Field[Ctx, Val]]): ObjectType[Ctx, Val] =
    ObjectType(Named.checkName(name), None, fieldsFn = Named.checkObjFieldsFn(fields), Nil)
  def apply[Ctx, Val: ClassTag](name: String, description: String, fields: List[Field[Ctx, Val]]): ObjectType[Ctx, Val] =
    ObjectType(Named.checkName(name), Some(description), fieldsFn = Named.checkObjFieldsFn(fields), Nil)
  def apply[Ctx, Val: ClassTag](name: String, interfaces: List[PossibleInterface[Ctx, Val]], fields: List[Field[Ctx, Val]]): ObjectType[Ctx, Val] =
    ObjectType(Named.checkName(name), None, fieldsFn = Named.checkObjFieldsFn(fields), interfaces map (_.interfaceType))
  def apply[Ctx, Val: ClassTag](name: String, description: String, interfaces: List[PossibleInterface[Ctx, Val]], fields: List[Field[Ctx, Val]]): ObjectType[Ctx, Val] =
    ObjectType(Named.checkName(name), Some(description), fieldsFn = Named.checkObjFieldsFn(fields), interfaces map (_.interfaceType))

  def apply[Ctx, Val: ClassTag](name: String, fieldsFn: () ⇒ List[Field[Ctx, Val]]): ObjectType[Ctx, Val] =
    ObjectType(Named.checkName(name), None, Named.checkObjFields(fieldsFn), Nil)
  def apply[Ctx, Val: ClassTag](name: String, description: String, fieldsFn: () ⇒ List[Field[Ctx, Val]]): ObjectType[Ctx, Val] =
    ObjectType(Named.checkName(name), Some(description), Named.checkObjFields(fieldsFn), Nil)
  def apply[Ctx, Val: ClassTag](name: String, interfaces: List[PossibleInterface[Ctx, Val]], fieldsFn: () ⇒ List[Field[Ctx, Val]]): ObjectType[Ctx, Val] =
    ObjectType(Named.checkName(name), None, Named.checkObjFields(fieldsFn), interfaces map (_.interfaceType))
  def apply[Ctx, Val: ClassTag](name: String, description: String, interfaces: List[PossibleInterface[Ctx, Val]], fieldsFn: () ⇒ List[Field[Ctx, Val]]): ObjectType[Ctx, Val] =
    ObjectType(Named.checkName(name), Some(description), Named.checkObjFields(fieldsFn), interfaces map (_.interfaceType))

  def subs[Ctx, Val: ClassTag](name: String, fields: List[Field[Ctx, Val]]): ObjectType[Ctx, Val] @@ SubscriptionTag[Int] =
    tag[SubscriptionTag[Int]](ObjectType(Named.checkName(name), None, fieldsFn = Named.checkObjFieldsFn(fields), Nil))
  def subs[Ctx, Val: ClassTag](name: String, description: String, fields: List[Field[Ctx, Val]]): ObjectType[Ctx, Val] =
    tag[SubscriptionTag[Int]](ObjectType(Named.checkName(name), Some(description), fieldsFn = Named.checkObjFieldsFn(fields), Nil))
  def subs[Ctx, Val: ClassTag](name: String, interfaces: List[PossibleInterface[Ctx, Val]], fields: List[Field[Ctx, Val]]): ObjectType[Ctx, Val] =
    tag[SubscriptionTag[Int]](ObjectType(Named.checkName(name), None, fieldsFn = Named.checkObjFieldsFn(fields), interfaces map (_.interfaceType)))
  def subs[Ctx, Val: ClassTag](name: String, description: String, interfaces: List[PossibleInterface[Ctx, Val]], fields: List[Field[Ctx, Val]]): ObjectType[Ctx, Val] =
    tag[SubscriptionTag[Int]](ObjectType(Named.checkName(name), Some(description), fieldsFn = Named.checkObjFieldsFn(fields), interfaces map (_.interfaceType)))

  def subs[Ctx, Val: ClassTag](name: String, fieldsFn: () ⇒ List[Field[Ctx, Val]]): ObjectType[Ctx, Val] =
    tag[SubscriptionTag[Int]](ObjectType(Named.checkName(name), None, Named.checkObjFields(fieldsFn), Nil))
  def subs[Ctx, Val: ClassTag](name: String, description: String, fieldsFn: () ⇒ List[Field[Ctx, Val]]): ObjectType[Ctx, Val] =
    tag[SubscriptionTag[Int]](ObjectType(Named.checkName(name), Some(description), Named.checkObjFields(fieldsFn), Nil))
  def subs[Ctx, Val: ClassTag](name: String, interfaces: List[PossibleInterface[Ctx, Val]], fieldsFn: () ⇒ List[Field[Ctx, Val]]): ObjectType[Ctx, Val] =
    tag[SubscriptionTag[Int]](ObjectType(Named.checkName(name), None, Named.checkObjFields(fieldsFn), interfaces map (_.interfaceType)))
  def subs[Ctx, Val: ClassTag](name: String, description: String, interfaces: List[PossibleInterface[Ctx, Val]], fieldsFn: () ⇒ List[Field[Ctx, Val]]): ObjectType[Ctx, Val] =
    tag[SubscriptionTag[Int]](ObjectType(Named.checkName(name), Some(description), Named.checkObjFields(fieldsFn), interfaces map (_.interfaceType)))

  implicit def acceptUnitCtx[Ctx, Val](objectType: ObjectType[Unit, Val]): ObjectType[Ctx, Val] =
    objectType.asInstanceOf[ObjectType[Ctx, Val]]
}

case class InterfaceType[Ctx, Val] private (
  name: String,
  description: Option[String] = None,
  fieldsFn: () ⇒ List[Field[Ctx, Val]],
  interfaces: List[InterfaceType[Ctx, _]],
  manualPossibleTypes: () ⇒ List[ObjectType[_, _]]
) extends ObjectLikeType[Ctx, Val] with AbstractType {
  def withPossibleTypes(possible: PossibleObject[Ctx, Val]*) = copy(manualPossibleTypes = () ⇒ possible.toList map (_.objectType))
  def withPossibleTypes(possible: () ⇒ List[PossibleObject[Ctx, Val]]) = copy(manualPossibleTypes = () ⇒ possible() map (_.objectType))
}

object InterfaceType {
  val emptyPossibleTypes: () ⇒ List[ObjectType[_, _]] = () ⇒ Nil

  def apply[Ctx, Val](name: String, fields: List[Field[Ctx, Val]]): InterfaceType[Ctx, Val] =
    InterfaceType(Named.checkName(name), None, fieldsFn = Named.checkIntFieldsFn(fields), Nil, emptyPossibleTypes)
  def apply[Ctx, Val](name: String, description: String, fields: List[Field[Ctx, Val]]): InterfaceType[Ctx, Val] =
    InterfaceType(Named.checkName(name), Some(description), fieldsFn = Named.checkIntFieldsFn(fields), Nil, emptyPossibleTypes)
  def apply[Ctx, Val](name: String, fields: List[Field[Ctx, Val]], interfaces: List[PossibleInterface[Ctx, Val]]): InterfaceType[Ctx, Val] =
    InterfaceType(Named.checkName(name), None, fieldsFn = Named.checkIntFieldsFn(fields), interfaces map (_.interfaceType), emptyPossibleTypes)
  def apply[Ctx, Val](name: String, description: String, fields: List[Field[Ctx, Val]], interfaces: List[PossibleInterface[Ctx, Val]]): InterfaceType[Ctx, Val] =
    InterfaceType(Named.checkName(name), Some(description), fieldsFn = Named.checkIntFieldsFn(fields), interfaces map (_.interfaceType), emptyPossibleTypes)

  def apply[Ctx, Val](name: String, fieldsFn: () ⇒ List[Field[Ctx, Val]]): InterfaceType[Ctx, Val] =
    InterfaceType(Named.checkName(name), None, Named.checkIntFields(fieldsFn), Nil, emptyPossibleTypes)
  def apply[Ctx, Val](name: String, description: String, fieldsFn: () ⇒ List[Field[Ctx, Val]]): InterfaceType[Ctx, Val] =
    InterfaceType(Named.checkName(name), Some(description), Named.checkIntFields(fieldsFn), Nil, emptyPossibleTypes)
  def apply[Ctx, Val](name: String, fieldsFn: () ⇒ List[Field[Ctx, Val]], interfaces: List[PossibleInterface[Ctx, Val]]): InterfaceType[Ctx, Val] =
    InterfaceType(Named.checkName(name), None, Named.checkIntFields(fieldsFn), interfaces map (_.interfaceType), emptyPossibleTypes)
  def apply[Ctx, Val](name: String, description: String, fieldsFn: () ⇒ List[Field[Ctx, Val]], interfaces: List[PossibleInterface[Ctx, Val]]): InterfaceType[Ctx, Val] =
    InterfaceType(Named.checkName(name), Some(description), Named.checkIntFields(fieldsFn), interfaces map (_.interfaceType), emptyPossibleTypes)
}

case class PossibleInterface[Ctx, Concrete](interfaceType: InterfaceType[Ctx, _])

object PossibleInterface extends PossibleInterfaceLowPrioImplicits {
  implicit def apply[Ctx, Abstract, Concrete](interface: InterfaceType[Ctx, Abstract])(implicit ev: PossibleType[Abstract, Concrete]): PossibleInterface[Ctx, Concrete] =
    PossibleInterface[Ctx, Concrete](interface)
}

trait PossibleInterfaceLowPrioImplicits {
  implicit def applyUnit[Ctx, Abstract, Concrete](interface: InterfaceType[Ctx, Abstract])(implicit ev: PossibleType[Abstract, Concrete]): PossibleInterface[Unit, Concrete] =
    PossibleInterface[Unit, Concrete](interface.asInstanceOf[InterfaceType[Unit, Abstract]])
}

case class PossibleObject[Ctx, Abstract](objectType: ObjectType[Ctx, _])

object PossibleObject {
  implicit def apply[Ctx, Abstract, Concrete](obj: ObjectType[Ctx, Concrete])(implicit ev: PossibleType[Abstract, Concrete]): PossibleObject[Ctx, Abstract] =
    PossibleObject[Ctx, Abstract](obj)

  implicit def applyUnit[Ctx, Abstract, Concrete](obj: ObjectType[Unit, Concrete])(implicit ev: PossibleType[Abstract, Concrete]): PossibleObject[Ctx, Abstract] =
    PossibleObject[Ctx, Abstract](obj.asInstanceOf[ObjectType[Ctx, Concrete]])
}

trait PossibleType[AbstractType, ConcreteType]

object PossibleType {
  private object SingletonPossibleType extends PossibleType[AnyRef, AnyRef]

  def create[AbstractType, ConcreteType] = SingletonPossibleType.asInstanceOf[PossibleType[AbstractType, ConcreteType]]

  implicit def InheritanceBasedPossibleType[Abstract, Concrete](implicit ev: Concrete <:< Abstract): PossibleType[Abstract, Concrete] =
    create[Abstract, Concrete]
}

case class UnionType[Ctx](
  name: String,
  description: Option[String] = None,
  types: List[ObjectType[Ctx, _]]) extends OutputType[Any] with CompositeType[Any] with AbstractType with NullableType with UnmodifiedType

case class Field[Ctx, Val] private (
    name: String,
    fieldType: OutputType[_],
    description: Option[String],
    arguments: List[Argument[_]],
    resolve: Context[Ctx, Val] ⇒ Action[Ctx, _],
    deprecationReason: Option[String],
    tags: List[FieldTag],
    complexity: Option[(Ctx, Args, Double) ⇒ Double],
    manualPossibleTypes: () ⇒ List[ObjectType[_, _]]) extends Named with HasArguments {
  def withPossibleTypes(possible: PossibleObject[Ctx, Val]*) = copy(manualPossibleTypes = () ⇒ possible.toList map (_.objectType))
  def withPossibleTypes(possible: () ⇒ List[PossibleObject[Ctx, Val]]) = copy(manualPossibleTypes = () ⇒ possible() map (_.objectType))
}

object Field {
  def apply[Ctx, Val, Res, Out](
      name: String,
      fieldType: OutputType[Out],
      description: Option[String] = None,
      arguments: List[Argument[_]] = Nil,
      resolve: Context[Ctx, Val] ⇒ Action[Ctx, Res],
      possibleTypes: ⇒ List[PossibleObject[_, _]] = Nil,
      tags: List[FieldTag] = Nil,
      complexity: Option[(Ctx, Args, Double) ⇒ Double] = None,
      deprecationReason: Option[String] = None)(implicit ev: ValidOutType[Res, Out]) =
    Field[Ctx, Val](Named.checkName(name), fieldType, description, arguments, resolve, deprecationReason, tags, complexity, () ⇒ possibleTypes map (_.objectType))

  def subs[Ctx, Val, Res, Out](
      name: String,
      fieldType: OutputType[Out],
      description: Option[String] = None,
      arguments: List[Argument[_]] = Nil,
      resolve: Context[Ctx, Val] ⇒ Action[Ctx, Res],
      possibleTypes: ⇒ List[PossibleObject[_, _]] = Nil,
      tags: List[FieldTag] = Nil,
      complexity: Option[(Ctx, Args, Double) ⇒ Double] = None,
      deprecationReason: Option[String] = None)(implicit ev: ValidOutType[Res, Out]) =
    tag[SubscriptionTag[Int]](Field[Ctx, Val](Named.checkName(name), fieldType, description, arguments, resolve, deprecationReason, tags, complexity, () ⇒ possibleTypes map (_.objectType)))

}

@implicitNotFound(msg = "${Res} is invalid type for the resulting GraphQL type ${Out}.")
trait ValidOutType[-Res, +Out]

object ValidOutType extends LowPrioValidOutType {
  implicit def validSubclass[Res, Out](implicit ev: Res <:< Out) = valid.asInstanceOf[ValidOutType[Res, Out]]
  implicit def validNothing[Out] = valid.asInstanceOf[ValidOutType[Nothing, Out]]
  implicit def validOption[Res, Out](implicit ev: Res <:< Out) = valid.asInstanceOf[ValidOutType[Res, Option[Out]]]

}

trait LowPrioValidOutType {
  val valid = new ValidOutType[Any, Any] {}

  implicit def validSeq[Res, Out](implicit ev: Res <:< Out) = valid.asInstanceOf[ValidOutType[Res, Seq[Out]]]
}

trait InputValue[T] {
  def name: String
  def inputValueType: InputType[_]
  def description: Option[String]
  def defaultValue: Option[(_, ToInput[_, _])]
}

case class Argument[T] private (
    name: String,
    argumentType: InputType[_],
    description: Option[String],
    defaultValue: Option[(_, ToInput[_, _])],
    fromInput: FromInput[_]) extends InputValue[T] with Named {

  if (!argumentType.isInstanceOf[OptionInputType[_]] && defaultValue.isDefined)
    throw new IllegalArgumentException(s"Argument '$name' is has NotNull type and defines a default value, which is not allowed! You need to either make this argument nullable or remove the default value.")

  def inputValueType = argumentType
}

object Argument {
  def apply[T, Default](
      name: String,
      argumentType: InputType[T],
      description: String,
      defaultValue: Default)(implicit toInput: ToInput[Default, _], fromInput: FromInput[T], res: ArgumentType[T]): Argument[res.Res] =
    Argument(Named.checkName(name), argumentType, Some(description), Some(defaultValue → toInput), fromInput)

  def apply[T, Default](
      name: String,
      argumentType: InputType[T],
      defaultValue: Default)(implicit toInput: ToInput[Default, _], fromInput: FromInput[T], res: ArgumentType[T]): Argument[res.Res] =
    Argument(Named.checkName(name), argumentType, None, Some(defaultValue → toInput), fromInput)

  def apply[T](
      name: String,
      argumentType: InputType[T],
      description: String)(implicit fromInput: FromInput[T], res: WithoutInputTypeTags[T]): Argument[res.Res] =
    Argument(Named.checkName(name), argumentType, Some(description), None, fromInput)

  def apply[T](
      name: String,
      argumentType: InputType[T])(implicit fromInput: FromInput[T], res: WithoutInputTypeTags[T]): Argument[res.Res] =
    Argument(Named.checkName(name), argumentType, None, None, fromInput)
}

trait WithoutInputTypeTags[T] {
  type Res
}

object WithoutInputTypeTags extends WithoutInputTypeTagsLowPrio {
  implicit def coercedArgTpe[T] = new WithoutInputTypeTags[T @@ CoercedScalaResult] {
    type Res = T
  }

  implicit def coercedOptArgTpe[T] = new WithoutInputTypeTags[Option[T @@ CoercedScalaResult]] {
    type Res = Option[T]
  }

  implicit def coercedSeqOptArgTpe[T] = new WithoutInputTypeTags[Seq[Option[T @@ CoercedScalaResult]]] {
    type Res = Seq[Option[T]]
  }

  implicit def coercedOptSeqArgTpe[T] = new WithoutInputTypeTags[Option[Seq[T @@ CoercedScalaResult]]] {
    type Res = Option[Seq[T]]
  }

  implicit def coercedOptSeqOptArgTpe[T] = new WithoutInputTypeTags[Option[Seq[Option[T @@ CoercedScalaResult]]]] {
    type Res = Option[Seq[Option[T]]]
  }

  implicit def ioArgTpe[T] = new WithoutInputTypeTags[T @@ InputObjectResult] {
    type Res = T
  }

  implicit def ioOptArgTpe[T] = new WithoutInputTypeTags[Option[T @@ InputObjectResult]] {
    type Res = Option[T]
  }

  implicit def ioSeqOptArgTpe[T] = new WithoutInputTypeTags[Seq[Option[T @@ InputObjectResult]]] {
    type Res = Seq[Option[T]]
  }

  implicit def ioOptSeqArgTpe[T] = new WithoutInputTypeTags[Option[Seq[T @@ InputObjectResult]]] {
    type Res = Option[Seq[T]]
  }

  implicit def ioOptSeqOptArgTpe[T] = new WithoutInputTypeTags[Option[Seq[Option[T @@ InputObjectResult]]]] {
    type Res = Option[Seq[Option[T]]]
  }
}

trait WithoutInputTypeTagsLowPrio {
  implicit def defaultArgTpe[T] = new WithoutInputTypeTags[T] {
    type Res = T
  }
}

trait ArgumentType[T] {
  type Res
}

object ArgumentType extends ArgumentTypeLowPrio {
  implicit def coercedArgTpe[T] = new ArgumentType[T @@ CoercedScalaResult] {
    type Res = T
  }

  implicit def coercedOptArgTpe[T] = new ArgumentType[Option[T @@ CoercedScalaResult]] {
    type Res = T
  }

  implicit def coercedSeqOptArgTpe[T] = new ArgumentType[Seq[Option[T @@ CoercedScalaResult]]] {
    type Res = Seq[Option[T]]
  }

  implicit def coercedOptSeqArgTpe[T] = new ArgumentType[Option[Seq[T @@ CoercedScalaResult]]] {
    type Res = Seq[T]
  }

  implicit def coercedOptSeqOptArgTpe[T] = new ArgumentType[Option[Seq[Option[T @@ CoercedScalaResult]]]] {
    type Res = Seq[Option[T]]
  }

  implicit def ioArgTpe[T] = new ArgumentType[T @@ InputObjectResult] {
    type Res = T
  }

  implicit def ioOptArgTpe[T] = new ArgumentType[Option[T @@ InputObjectResult]] {
    type Res = T
  }

  implicit def ioSeqOptArgTpe[T] = new ArgumentType[Seq[Option[T @@ InputObjectResult]]] {
    type Res = Seq[Option[T]]
  }

  implicit def ioOptSeqArgTpe[T] = new ArgumentType[Option[Seq[T @@ InputObjectResult]]] {
    type Res = Seq[T]
  }

  implicit def ioOptSeqOptArgTpe[T] = new ArgumentType[Option[Seq[Option[T @@ InputObjectResult]]]] {
    type Res = Seq[Option[T]]
  }
}

trait ArgumentTypeLowPrio extends ArgumentTypeLowestPrio {
  implicit def optionArgTpe[T] = new ArgumentType[Option[T]] {
    type Res = T
  }
}

trait ArgumentTypeLowestPrio {
  implicit def defaultArgTpe[T] = new ArgumentType[T] {
    type Res = T
  }
}

case class EnumType[T](
    name: String,
    description: Option[String] = None,
    values: List[EnumValue[T]]) extends InputType[T @@ CoercedScalaResult] with OutputType[T] with LeafType with NullableType with UnmodifiedType with Named {
  lazy val byName = values groupBy (_.name) mapValues (_.head)
  lazy val byValue = values groupBy (_.value) mapValues (_.head)

  def coerceUserInput(value: Any): Either[Violation, (T, Boolean)] = value match {
    case name: String ⇒ byName get name map (v ⇒ Right(v.value → v.deprecationReason.isDefined)) getOrElse Left(EnumValueCoercionViolation(name))
    case v if byValue exists (_._1 == v) ⇒ Right(v.asInstanceOf[T] → byValue(v.asInstanceOf[T]).deprecationReason.isDefined)
    case _ ⇒ Left(EnumCoercionViolation)
  }

  def coerceInput(value: ast.Value): Either[Violation, (T, Boolean)] = value match {
    case ast.EnumValue(name, _) ⇒ byName get name map (v ⇒ Right(v.value → v.deprecationReason.isDefined)) getOrElse Left(EnumValueCoercionViolation(name))
    case _ ⇒ Left(EnumCoercionViolation)
  }

  def coerceOutput(value: T) = ast.EnumValue(byValue(value).name)
}

case class EnumValue[+T](
  name: String,
  description: Option[String] = None,
  value: T,
  deprecationReason: Option[String] = None) extends Named

case class InputObjectType[T] private (
  name: String,
  description: Option[String] = None,
  fieldsFn: () ⇒ List[InputField[_]]
) extends InputType[T @@ InputObjectResult] with NullableType with UnmodifiedType with Named {
  lazy val fields = fieldsFn()
  lazy val fieldsByName = fields groupBy(_.name) mapValues(_.head)
}

object InputObjectType {
  type DefaultInput = Map[String, Any]

  def apply[T](name: String, fields: List[InputField[_]])(implicit res: InputObjectDefaultResult[T]): InputObjectType[res.Res] =
    InputObjectType(Named.checkName(name), None, fieldsFn = Named.checkIntFieldsFn(fields))
  def apply[T](name: String, description: String, fields: List[InputField[_]])(implicit res: InputObjectDefaultResult[T]): InputObjectType[res.Res] =
    InputObjectType(Named.checkName(name), Some(description), fieldsFn = Named.checkIntFieldsFn(fields))

  def apply[T](name: String, fieldsFn: () ⇒ List[InputField[_]])(implicit res: InputObjectDefaultResult[T]): InputObjectType[res.Res] =
    InputObjectType(Named.checkName(name), None, Named.checkIntFields(fieldsFn))
  def apply[T](name: String, description: String, fieldsFn: () ⇒ List[InputField[_]])(implicit res: InputObjectDefaultResult[T]): InputObjectType[res.Res] =
    InputObjectType(Named.checkName(name), Some(description), Named.checkIntFields(fieldsFn))
}

trait InputObjectDefaultResult[T] {
  type Res
}

object InputObjectDefaultResult extends InputObjectDefaultResultLowPrio {
  implicit def nothingResult = new InputObjectDefaultResult[Nothing] {
    override type Res = InputObjectType.DefaultInput
  }
}

trait InputObjectDefaultResultLowPrio {
  implicit def defaultResult[T] = new InputObjectDefaultResult[T] {
    override type Res = T
  }
}

case class InputField[T] private (
    name: String,
    fieldType: InputType[T],
    description: Option[String],
    defaultValue: Option[(_, ToInput[_, _])]) extends InputValue[T] with Named {

  if (!fieldType.isInstanceOf[OptionInputType[_]] && defaultValue.isDefined)
    throw new IllegalArgumentException(s"Input field '$name' is has NotNull type and defines a default value, which is not allowed! You need to either make this fields nullable or remove the default value.")

  def inputValueType = fieldType
}

object InputField {
  def apply[T, Default](name: String, fieldType: InputType[T], description: String, defaultValue: Default)(implicit toInput: ToInput[Default, _], res: WithoutInputTypeTags[T]): InputField[res.Res] =
    InputField(name, fieldType, Some(description), Some(defaultValue → toInput)).asInstanceOf[InputField[res.Res]]

  def apply[T, Default](name: String, fieldType: InputType[T], defaultValue: Default)(implicit toInput: ToInput[Default, _], res: WithoutInputTypeTags[T]): InputField[res.Res] =
    InputField(name, fieldType, None, Some(defaultValue → toInput)).asInstanceOf[InputField[res.Res]]

  def apply[T, Default](name: String, fieldType: InputType[T], description: String)(implicit res: WithoutInputTypeTags[T]): InputField[res.Res] =
    InputField(name, fieldType, Some(description), None).asInstanceOf[InputField[res.Res]]

  def apply[T, Default](name: String, fieldType: InputType[T])(implicit res: WithoutInputTypeTags[T]): InputField[res.Res] =
    InputField(name, fieldType, None, None).asInstanceOf[InputField[res.Res]]
}

case class ListType[T](ofType: OutputType[T]) extends OutputType[Seq[T]] with NullableType
case class ListInputType[T](ofType: InputType[T]) extends InputType[Seq[T]] with NullableType

case class OptionType[T](ofType: OutputType[T]) extends OutputType[Option[T]]
case class OptionInputType[T] (ofType: InputType[T]) extends InputType[Option[T]]

sealed trait HasArguments {
  def arguments: List[Argument[_]]
}

case class Directive(
  name: String,
  description: Option[String] = None,
  arguments: List[Argument[_]] = Nil,
  shouldInclude: DirectiveContext ⇒ Boolean,
  onOperation: Boolean,
  onFragment: Boolean,
  onField: Boolean) extends HasArguments

case class Schema[Ctx, Val] private (
    query: ObjectType[Ctx, Val],
    mutation: Option[ObjectType[Ctx, Val]],
    subscription: Option[ObjectType[Ctx, Val]],
    additionalTypes: List[Type with Named],
    directives: List[Directive],
    validationRules: List[SchemaValidationRule]) {
  lazy val types: Map[String, (Int, Type with Named)] = {
    def updated(priority: Int, name: String, tpe: Type with Named, result: Map[String, (Int, Type with Named)]) =
      if (result contains name) result else result.updated(name, priority → tpe)

    def collectTypes(parentInfo: String, priority: Int, tpe: Type, result: Map[String, (Int, Type with Named)]): Map[String, (Int, Type with Named)] = {
      tpe match {
        case null ⇒ throw new IllegalStateException(
          s"A `null` value was provided instead of type for $parentInfo.\n" +
          "This can happen if you have recursive type definition or circular references withing your type graph.\n" +
          "Please use no-arg function to provide fields for such types.\n" +
          "You can find more info in the docs: http://sangria-graphql.org/learn/#circular-references-and-recursive-types")
        case t: Named if result contains t.name ⇒ result
        case OptionType(ofType) ⇒ collectTypes(parentInfo, priority, ofType, result)
        case OptionInputType(ofType) ⇒ collectTypes(parentInfo, priority, ofType, result)
        case ListType(ofType) ⇒ collectTypes(parentInfo, priority, ofType, result)
        case ListInputType(ofType) ⇒ collectTypes(parentInfo, priority, ofType, result)

        case t @ ScalarType(name, _, _, _, _, _) ⇒ updated(priority, name, t, result)
        case t @ EnumType(name, _, _) ⇒ updated(priority, name, t, result)
        case t @ InputObjectType(name, _, _) ⇒
          t.fields.foldLeft(updated(priority, name, t, result)) {case (acc, field) ⇒
            collectTypes(s"a field '${field.name}' of '$name' input object type", priority, field.fieldType, acc)}
        case t: ObjectLikeType[_, _] ⇒
          val own = t.fields.foldLeft(updated(priority, t.name, t, result)) {
            case (acc, field) ⇒
              val fromArgs = field.arguments.foldLeft(collectTypes(s"a field '${field.name}' of '${t.name}' type", priority, field.fieldType, acc)) {
                case (aacc, arg) ⇒ collectTypes(s"an argument '${arg.name}' defined in field '${field.name}' of '${t.name}' type", priority, arg.argumentType, aacc)
              }

              field.manualPossibleTypes().foldLeft(fromArgs) {
                case (acc, objectType) ⇒ collectTypes(s"a manualPossibleType defined in '${t.name}' type", priority, objectType, acc)
              }
          }

          val withPossible = t match {
            case i: InterfaceType[_, _] ⇒
              i.manualPossibleTypes().foldLeft(own) {
                case (acc, objectType) ⇒ collectTypes(s"a manualPossibleType defined in '${i.name}' type", priority, objectType, acc)
              }
            case _ ⇒ own
          }

          t.interfaces.foldLeft(withPossible) {
            case (acc, interface) ⇒ collectTypes(s"an interface defined in '${t.name}' type", priority, interface, acc)
          }
        case t @ UnionType(name, _, types) ⇒
          types.foldLeft(updated(priority, name, t, result)) {case (acc, tpe) ⇒ collectTypes(s"a '$name' type", priority, tpe, acc)}
      }
    }

    val schemaTypes = collectTypes("a '__Schema' type", 30, introspection.__Schema, Map(BuiltinScalars map (s ⇒ s.name → (40, s)): _*))
    val queryTypes = collectTypes("a query type", 20, query, schemaTypes)
    val queryTypesWithAdditions = queryTypes ++ additionalTypes.map(t ⇒ t.name → (10, t))
    val queryAndSubTypes = mutation map (collectTypes("a mutation type", 10, _, queryTypesWithAdditions)) getOrElse queryTypesWithAdditions
    val queryAndSubAndMutTypes = subscription map (collectTypes("a subscription type", 10, _, queryTypesWithAdditions)) getOrElse queryAndSubTypes

    queryAndSubAndMutTypes
  }

  lazy val typeList = types.values.toList.sortBy(t ⇒ t._1 + t._2.name).map(_._2)

  lazy val allTypes = types collect {case (name, (_, tpe)) ⇒ name → tpe}
  lazy val inputTypes = types collect {case (name, (_, tpe: InputType[_])) ⇒ name → tpe}
  lazy val outputTypes = types collect {case (name, (_, tpe: OutputType[_])) ⇒ name → tpe}
  lazy val scalarTypes = types collect {case (name, (_, tpe: ScalarType[_])) ⇒ name → tpe}
  lazy val unionTypes: Map[String, UnionType[_]] =
    types.filter(_._2._2.isInstanceOf[UnionType[_]]).mapValues(_._2.asInstanceOf[UnionType[_]])

  lazy val directivesByName = directives groupBy (_.name) mapValues (_.head)

  def getInputType(tpe: ast.Type): Option[InputType[_]] = tpe match {
    case ast.NamedType(name, _) ⇒ inputTypes get name map (OptionInputType(_))
    case ast.NotNullType(ofType, _) ⇒ getInputType(ofType) collect {case OptionInputType(ot) ⇒ ot}
    case ast.ListType(ofType, _) ⇒ getInputType(ofType) map (t ⇒ OptionInputType(ListInputType(t)))
  }

  def getOutputType(tpe: ast.Type, topLevel: Boolean = false): Option[OutputType[_]] = tpe match {
    case ast.NamedType(name, _) ⇒ outputTypes get name map (ot ⇒ if (topLevel) ot else OptionType(ot))
    case ast.NotNullType(ofType, _) ⇒ getOutputType(ofType) collect {case OptionType(ot) ⇒ ot}
    case ast.ListType(ofType, _) ⇒ getOutputType(ofType) map (ListType(_))
  }

  lazy val directImplementations: Map[String, List[ObjectLikeType[_, _]]] = {
    typeList
      .collect{case objectLike: ObjectLikeType[_, _] ⇒ objectLike}
      .flatMap(objectLike ⇒ objectLike.interfaces map (_.name → objectLike))
      .groupBy(_._1)
      .mapValues(_ map (_._2))
  }

  lazy val implementations: Map[String, List[ObjectType[_, _]]] = {
    def findConcreteTypes(tpe: ObjectLikeType[_, _]): List[ObjectType[_, _]] = tpe match {
      case obj: ObjectType[_, _] ⇒ obj :: Nil
      case interface: InterfaceType[_, _] ⇒ directImplementations(interface.name) flatMap findConcreteTypes
    }

    directImplementations map {
      case (name, directImpls) ⇒ name → directImpls.flatMap(findConcreteTypes).groupBy(_.name).map(_._2.head).toList
    }
  }

  lazy val possibleTypes: Map[String, List[ObjectType[_, _]]] =
    implementations ++ unionTypes.values.map(ut ⇒ ut.name → ut.types)

  def isPossibleType(baseTypeName: String, tpe: ObjectType[_, _]) =
    possibleTypes get baseTypeName exists (_ exists (_.name == tpe.name))

  val validationErrors = validationRules flatMap (_.validate(this))

  if (validationErrors.nonEmpty) throw SchemaValidationException(validationErrors)
}

object Schema {
  def apply[Ctx, Val, ST <: ObjectType[Ctx, Val]](
      query: ObjectType[Ctx, Val],
      mutation: Option[ObjectType[Ctx, Val]] = None,
      subscription: Option[ST] = None,
      additionalTypes: List[Type with Named] = Nil,
      directives: List[Directive] = BuiltinDirectives,
      validationRules: List[SchemaValidationRule] = SchemaValidationRule.default)(implicit st: SchemaType[Ctx, Val, ST]): st.Result =
    st.get(Schema[Ctx, Val](query, mutation, subscription, additionalTypes, directives, validationRules))
}

trait SubscriptionTag[T]

trait SchemaType[Ctx, Val, +ST] {
  type Result

  def get(schema: Schema[Ctx, Val]): Result
}

object SchemaType extends SchemaTypeLowPrio {
  implicit def defaultUndefined[Ctx, Val] = new SchemaType[Ctx, Val, Nothing] {
    type Result = Schema[Ctx, Val]

    def get(schema: Schema[Ctx, Val]) = schema
  }
}

trait SchemaTypeLowPrio {
  implicit def subscriptionObjectType[Ctx, Val, S] = new SchemaType[Ctx, Val, ObjectType[Ctx, Val] @@ SubscriptionTag[S]] {
    type Result = Schema[Ctx, Val] @@ SubscriptionTag[S]

    def get(schema: Schema[Ctx, Val]) = tag[SubscriptionTag[S]][Schema[Ctx, Val]](schema)
  }

  implicit def normalObjectType[Ctx, Val] = new SchemaType[Ctx, Val, ObjectType[Ctx, Val]] {
    type Result = Schema[Ctx, Val]

    def get(schema: Schema[Ctx, Val]) = schema
  }
}
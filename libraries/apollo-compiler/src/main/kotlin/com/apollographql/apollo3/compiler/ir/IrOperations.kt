package com.apollographql.apollo3.compiler.ir

import com.apollographql.apollo3.api.BTerm
import com.apollographql.apollo3.api.BVariable
import com.apollographql.apollo3.api.BooleanExpression
import com.apollographql.apollo3.api.containsPossibleTypes
import com.apollographql.apollo3.ast.GQLFragmentDefinition
import com.apollographql.apollo3.ast.GQLType

/**
 * Intermediate representation (IR)
 *
 * Compared to the GraphQL AST, the IR:
 * - Transforms [com.apollographql.apollo3.ast.GQLField] into [IrProperty] and [IrModel]
 * - moves @include/@skip directives on inline fragments and object fields to their children selections
 * - interprets @deprecated directives
 * - coerces argument values and resolves defaultValue
 * - infers fragment variables
 * - registers used types and fragments
 * - more generally removes all references to the GraphQL AST and "embeds" type definitions/field definitions
 *
 * Additional notes:
 * - In order to ensure reproducibility, prefer using [List] instead of [Set] so that the order is guaranteed
 * - The IR doesn't escape identifiers because different targets might have different escaping rules. The names
 * found in the IR are as found in the GraphQL documents
 */
internal data class DefaultIrOperations(
    val operations: List<IrOperation>,
    val fragments: List<IrFragmentDefinition>,
    val usedTypes: Set<String>,
    val usedFields: Map<String, Set<String>>,

    val codegenModels: String,
    val flattenModels: Boolean,
    val decapitalizeFields: Boolean,
    val generateDataBuilders: Boolean,

    override val fragmentDefinitions: List<GQLFragmentDefinition>
): IrOperations

interface IrOperations {
  val fragmentDefinitions: List<GQLFragmentDefinition>
}

internal data class IrOperation(
    val name: String,
    val operationType: IrOperationType,
    val typeCondition: String,
    val variables: List<IrVariable>,
    val description: String?,
    val selectionSets: List<IrSelectionSet>,
    /**
     * the executableDocument sent to the server
     */
    val sourceWithFragments: String,
    val filePath: String,
    val responseBasedDataModelGroup: IrModelGroup?,
    val dataProperty: IrProperty,
    val dataModelGroup: IrModelGroup,
)

internal data class IrSelectionSet(
    /**
     * a name for this [IrSelectionSet]. This name is unique across all [IrSelectionSet] for a given operation/fragment definition
     */
    val name: String,
    /**
     * true if this is the root selection set for this operation/fragment definition
     */
    val isRoot: Boolean,
    val selections: List<IrSelection>,
)

internal sealed interface IrSelection

internal data class IrField(
    val name: String,
    val alias: String?,
    val type: IrTypeRef,
    val condition: BooleanExpression<BVariable>,
    val arguments: List<IrArgument>,
    val selectionSetName: String?,
) : IrSelection

internal data class IrArgument(
    val name: String,
    val value: IrValue,
    val isKey: Boolean = false,
    val isPagination: Boolean = false,
)

internal sealed interface IrTypeRef
internal data class IrNonNullTypeRef(val ofType: IrTypeRef) : IrTypeRef
internal data class IrListTypeRef(val ofType: IrTypeRef) : IrTypeRef
internal data class IrNamedTypeRef(val name: String) : IrTypeRef

internal data class IrFragment(
    val typeCondition: String,
    val possibleTypes: List<String>,
    val condition: BooleanExpression<BVariable>,
    /**
     * The name of the [IrSelectionSet] that contains the [IrSelection] for this inline fragment
     * or null for fragments spreads (because the [IrSelectionSet] is defined in the fragment
     */
    val selectionSetName: String?,
    /**
     * The name of the fragment for fragment spreads or null for inline fragments
     */
    val name: String?,
) : IrSelection

internal data class IrFragmentDefinition(
    val name: String,
    val description: String?,
    val filePath: String,
    /**
     * Fragments do not have variables per-se (as of writing) but we can infer them from the document
     * Default values will always be null for those
     */
    val variables: List<IrVariable>,
    val typeCondition: String,
    val selectionSets: List<IrSelectionSet>,
    val interfaceModelGroup: IrModelGroup?,
    val dataProperty: IrProperty,
    val dataModelGroup: IrModelGroup,
    val source: String
)

internal sealed class IrOperationType(val typeName: String) {
  val name: String
    get() {
      return when (this) {
        is Query -> "Query"
        is Mutation -> "Mutation"
        is Subscription -> "Subscription"
      }
    }

  class Query(name: String) : IrOperationType(name)
  class Mutation(name: String) : IrOperationType(name)
  class Subscription(name: String) : IrOperationType(name)
}

/**
 * Information about a field that is going to be turned into an [IrProperty]. This merges fields and replaces directives
 * by things that are easier to use from codegen (description, deprecation, etc...)
 *
 * [type] can be used to be resolved to a model. It is made nullable if this field has an `@include` or `@defer` condition
 *
 * - For merged fields, [IrFieldInfo] is the information that is common to all merged fields
 * - For synthetic fields, it is constructed by hand
 *
 * TODO: maybe merge this with [IrProperty]
 */
internal data class IrFieldInfo(
    /**
     * The responseName of this field (or synthetic name)
     */
    val responseName: String,

    /**
     * from the fieldDefinition.
     *
     * It might contain IrModelType that point to generated models
     */
    val type: IrType,

    /**
     * The GraphQL type of the field needed to build the CompiledField or null for synthetic fields
     *
     * TODO: CompiledField duplicates "operation_document" so we could certainly remove it (and gqlType too)
     */
    val gqlType: GQLType?,

    /**
     * from the fieldDefinition
     *
     * This can technically differ if the field is implemented on different objects/interfaces.
     * For convenience, we take the value of the first encountered field
     */
    val description: String?,

    /**
     * from the fieldDefinition directives
     *
     * This can technically differ if the field is implemented on different objects/interfaces.
     * For convenience, we take the value of the first encountered field
     */
    val deprecationReason: String?,

    /**
     * from the fieldDefinition directives
     */
    val optInFeature: String?,
)

internal sealed class IrAccessor {
  abstract val returnedModelId: String
}

internal data class IrFragmentAccessor(
    val fragmentName: String,
    override val returnedModelId: String,
) : IrAccessor()

internal data class IrSubtypeAccessor(
    val typeSet: TypeSet,
    override val returnedModelId: String,
) : IrAccessor()

/**
 * A class or interface representing a GraphQL object field
 *
 * Monomorphic fields will always be represented by a class while polymorphic fields will involve interfaces
 */
internal data class IrModel(
    val modelName: String,
    /**
     * The path to this field. See [IrModelType] for more details
     */
    val id: String,
    /**
     * The typeSet of this model.
     * Used by the adapters for ordering/making the code look nice but has no runtime impact
     */
    val typeSet: TypeSet,
    val properties: List<IrProperty>,
    /**
     * The possible types
     * Used by the polymorphic adapter to generate the `when` statement that chooses the concrete adapter
     * to delegate to
     */
    val possibleTypes: List<String>,
    val accessors: List<IrAccessor>,
    /**
     * A list of paths to interfaces that the model implements
     */
    val implements: List<String>,
    /**
     * Nested models. Might be empty if the models are flattened
     */
    val modelGroups: List<IrModelGroup>,
    val isInterface: Boolean,
    val isFallback: Boolean,
)

/**
 * @param condition a condition for reading the property
 * @param requiresBuffering true if this property contains synthetic properties and needs to be buffered
 */
internal data class IrProperty(
    val info: IrFieldInfo,
    val override: Boolean,
    val condition: BooleanExpression<BTerm>,
    val requiresBuffering: Boolean,
) {
  /**
   * synthetic properties are special as we need to rewind the reader before reading them
   * They are read in a second pass and are not real json names
   */
  val isSynthetic: Boolean
    get() = info.gqlType == null

  /**
   * whether this field requires a typename to determine if we should parse it or not.
   * This is true for synthetic fragment fields on polymorphic fields
   */
  val requiresTypename: Boolean
    get() = condition.containsPossibleTypes()
}

internal data class IrModelGroup(
    val baseModelId: String,
    val models: List<IrModel>,
)

internal data class IrVariable(
    val name: String,
    val defaultValue: IrValue?,
    val type: IrType,
)

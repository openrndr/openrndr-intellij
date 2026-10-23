package org.openrndr.plugin.intellij

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionSorter
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.impl.SeparatorLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtUserType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMember
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import com.intellij.openapi.util.Key
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResult
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementRenderer
import com.intellij.codeInsight.lookup.LookupElementWeigher
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.ColorIcon
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.analysis.api.symbols.KaAnonymousFunctionSymbol
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotation
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.idea.base.codeInsight.ShortenReferencesFacility
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import java.awt.Color

/**
 * Color swatches and ordering in the completion list, next to the gutter swatches of [OpenrndrColorProvider]:
 * - headed [Sections]: after `ColorRGBa.`, constructors and factory functions, then named colors; after a color
 *   value (`ColorRGBa.PINK.`, `myColor.`), properties, linear operations (shown as `ColorRGBa + ColorRGBa`),
 *   operations, queries and conversions; after `drawer.`, drawing properties, transforms, and text, shapes,
 *   images and advanced drawing; in a program (`program.`, `this.`, or plain completion inside `program { }`), events,
 *   drawing, images, text, time, input and coroutines; in `transform { }` (TransformBuilder), its own transforms;
 *   in `renderTarget(…) { }` (RenderTargetBuilder), attachments it creates, then attachments of existing buffers;
 *   after a render target, color or depth buffer, vertex or index buffer, a texture, a shader, shade style, compute
 *   style or vertex format (also inside `shadeStyle { }`, `computeStyle { }` and `vertexFormat { }`), its members by kind per class, then the rest of
 *   its API as assorted; in `compose { }` / `layer { }` (orx-compositor) and after a Layer or Composite, drawing,
 *   layers, filters and properties; in the first argument of `post(…)` there, every Filter on the classpath, the
 *   `Filter1to1`s of orx-fx by package (Blur, Color, Edges, …) and the rest as assorted; in `blend(…)`, the
 *   `Filter2to1`s: orx-fx's blend modes, then all others; after a filter (or in its `post(…) { }` block), its
 *   parameters, applying, properties and lifecycle; for any class, a Parameters section first with the members
 *   annotated with orx-parameters' `@DoubleParameter`, `@ColorParameter`, …;
 *   after a vector, its components, operators, geometry, queries, swizzles and conversions; after a List, Set, Map or
 *   String, a Spatial section for collections of vectors (`bounds`), then its common functions by kind (read, write, remove, sort, query, search, filter, map,
 *   random) and the rest of its API as assorted; after `mouse.` and `keyboard.`, properties and events; after any other value whose
 *   type has OPENRNDR `Event<…>` properties, those events.
 *   The headings use the
 *   platform's internal separator item, since its grouped completion drops headings as soon as items from other
 *   contributors (postfix templates) follow the groups;
 * - after `ColorRGBa.` / `ColorXYZa.` / …, for named colors (`RED`, orx presets like `ORANGE_RED`), evaluated
 *   with the project's own OPENRNDR;
 * - for color variables declared in the same file (`val myColor = rgb(…)`), using the gutter's color.
 *
 * Items keep coming from the other contributors; this only adds an icon, computed in the background when an
 * item is shown.
 */
class OpenrndrColorCompletion : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? KtFile ?: return
        if (!Openrndr.isProject(file.project)) return
        val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return

        val receiver = colorReceiver(parameters)
        // Color variables of this file that are declared before the caret, by name; the nearest one wins
        val variables = PsiTreeUtil.findChildrenOfType(file, KtProperty::class.java)
            .filter { it.initializer != null && it.textOffset < parameters.offset }
            .associateBy { it.name }

        val runtime = OpenrndrColorRuntime.getInstance(file.project)
        val qualified = (parameters.position.parent as? KtNameReferenceExpression)?.parent as? KtDotQualifiedExpression
        val receiverStart = qualified?.receiverExpression?.textRange?.startOffset ?: 0

        // After `ColorRGBa.`: constructors and factories, then presets. After a color value (`ColorRGBa.PINK.`,
        // `myColor.`): properties, operations, queries and conversions. All from the project's OPENRNDR.
        val members = receiver?.let { runtime.companionMembers(module, "org.openrndr.color.$it") }
        // After a color value: the first sentence of a member's KDoc, shown instead of Kotlin's "for ColorRGBa in …"
        var valueDocs: ((String) -> String?)? = null
        // Sections whose functions do not show a `Unit` return type (TransformBuilder, Drawer)
        var hideUnit = false
        // After a color value: its class name (`ColorRGBa`), for showing operators as `ColorRGBa + ColorRGBa`
        var valueClass: String? = null
        // The type of the value before the dot (`ColorRGBa.PINK.`, `myColor.`, `drawer.`), when not `ColorRGBa.` itself
        val valueType = if (receiver == null && qualified != null) valueClass(file, qualified) else null
        // Without a dot: the innermost implicit receiver this plugin has sections for (TransformBuilder in
        // `transform { }`, Program in `program { }` and `extend { }`)
        val implicitType = if (qualified == null) innermostKnownReceiver(file, parameters.offset) else null
        val inTransformBuilder = (valueType ?: implicitType)?.let(::binaryName) == TRANSFORM_BUILDER
        val inRenderTargetBuilder = (valueType ?: implicitType)?.let(::binaryName) == RENDER_TARGET_BUILDER
        // List, Set and Map values: the sections of that kind of collection
        val collectionSections = valueType?.let { COLLECTION_SECTIONS[binaryName(it)] }
        val inList = collectionSections != null
        val inVector = valueType != null && binaryName(valueType) in VECTOR_CLASSES
        // `post(<caret>)` / `blend(<caret>)` on a Layer (in compose { }): the filters, by orx-fx package
        val filterCall = filterCall(file, parameters, qualified)
        val filters = filterCall?.let { call ->
            OpenrndrFilters.filterClasses(module).filter { call == "post" || it.is2to1 }
        }
        val filterNames = filters?.mapTo(HashSet()) { it.qualifiedName }
        // RenderTarget, ColorBuffer, DepthBuffer, VertexBuffer, IndexBuffer, the textures, Shader, ShadeStyle,
        // VertexFormat, Layer and Composite: that class's sections, after a value or as the receiver of a block
        // (`shadeStyle { }`, `compose { }`)
        // A subclass gets the sections of the nearest class that has them (BoxBlur those of Filter), and every
        // class its orx-parameters (`@DoubleParameter var spread`) in a Parameters section first
        val classType: ClassId? = valueType ?: implicitType
        val info = if (filters == null && classType != null) classInfo(file, classType) else null
        val className = classType?.let(::binaryName)
        val bufferKey = if (info == null || className == null) null
        else if (className in BUFFER_SECTIONS) className else info.supertypes.firstOrNull(BUFFER_SECTIONS::containsKey)
        val bufferType = classType?.takeIf { bufferKey != null }
        val bufferSections = if (bufferKey != null && info != null) withParameters(BUFFER_SECTIONS.getValue(bufferKey), info.parameters) else null
        val bufferOwners = if (bufferKey != null && info != null && className != null) {
            setOf(className, bufferKey) + BUFFER_SUPERTYPES[bufferKey].orEmpty() + info.supertypes
        } else {
            emptySet()
        }
        // Any other class: its orx-parameters, and after a value its OPENRNDR `Event<…>` properties
        val otherParameters = if (bufferKey == null) info?.parameters.orEmpty() else emptyList()
        val otherEvents = if (bufferKey == null && valueType != null) eventProperties(file, valueType) else emptyList()
        // post: the orx-fx sections in package order, then Assorted; blend: orx-fx's blend modes, then all other Filter2to1s
        val filterTitles = filters?.associate { filter ->
            filter.qualifiedName to when {
                filterCall == "blend" -> if (filter.packageName == OpenrndrFilters.FX_BLEND) BLEND_MODES else FILTER_2TO1
                else -> filter.section?.replaceFirstChar(Char::uppercaseChar) ?: ASSORTED
            }
        }
        val sections: Sections? = when {
            filters != null && filterTitles != null -> {
                val titles = if (filterCall == "blend") listOf(BLEND_MODES, FILTER_2TO1)
                else filterTitles.values.filter { it != ASSORTED }.distinct().sorted() + ASSORTED
                // Alphabetical within a section; the heading takes slot 0 of each section
                val places = filters.groupBy { filterTitles[it.qualifiedName] }
                    .flatMap { (_, inSection) -> inSection.sortedBy { it.name }.mapIndexed { i, f -> f.qualifiedName to i } }.toMap()
                Sections(titles) { item ->
                    val filter = item.getUserData(FILTER_CLASS) ?: return@Sections null
                    titles.indexOf(filterTitles[filter.qualifiedName]) to (places[filter.qualifiedName] ?: 0).coerceAtMost(98)
                }
            }
            // Vector2 / Vector3 / Vector4: components, operators (shown as operators), geometry, queries, swizzles, conversions
            inVector -> runtime.vectorMembers(module, binaryName(valueType))?.let { vector ->
                valueDocs = { name -> docSummary(file, valueType, name, vector.extensionPackages[name]) }
                valueClass = valueType.shortClassName.asString()
                Sections(listOf(PROPERTIES, LINEAR_OPERATIONS, GEOMETRY, QUERY, SWIZZLE, CONVERT)) { item ->
                    val name = item.lookupString
                    when (name) {
                        in vector.properties -> 0 to 0
                        in vector.operators -> 1 to OPERATORS.keys.indexOf(name).coerceAtLeast(0)
                        in vector.geometry -> 2 to place(GEOMETRY_ORDER, name)
                        in vector.queries -> 3 to place(VECTOR_QUERY_ORDER, name)
                        in vector.swizzles -> 4 to 0
                        in vector.conversions -> 5 to 0
                        else -> null
                    }
                }
            }
            // List / MutableList: the common functions by kind, then every other collection function in Assorted
            inList -> {
                valueDocs = { name -> docSummary(file, valueType, name, "kotlin.collections") }
                // Spatial (extensions on lists of vectors: bounds, average, …) first, then the type's own sections
                Sections(listOf(SPATIAL) + collectionSections.map { it.first } + ASSORTED) { item ->
                    val section = item.getUserData(LIST_SECTION) ?: return@Sections null
                    val names = if (section == 0) SPATIAL_ORDER else collectionSections.getOrNull(section - 1)?.second
                    section to (names?.indexOf(item.lookupString)?.takeIf { it >= 0 } ?: 90)
                }
            }
            // TransformBuilder's own members and extensions only: a top-level `translate(…): Matrix44` stays out
            inTransformBuilder -> {
                hideUnit = true
                val builder = ClassId.topLevel(FqName(TRANSFORM_BUILDER))
                valueDocs = { name -> docSummary(file, builder, name, null) }
                Sections(listOf(TRANSFORM)) { item ->
                    if (item.getUserData(TRANSFORM_BUILDER_MEMBER) == true) 0 to place(TRANSFORM_BUILDER_ORDER, item.lookupString) else null
                }
            }
            // RenderTargetBuilder's attachments: the ones it creates, then the ones of existing buffers and textures
            inRenderTargetBuilder -> {
                hideUnit = true
                val builder = ClassId.topLevel(FqName(RENDER_TARGET_BUILDER))
                valueDocs = { name -> docSummary(file, builder, name, null) }
                Sections(listOf(ATTACH_NEW, ATTACH_EXISTING)) { item ->
                    item.getUserData(BUILDER_SECTION)?.let { it to place(ATTACHMENT_ORDER, item.lookupString) }
                }
            }
            members != null -> Sections(listOf(CONSTRUCTORS, PRESETS)) { item ->
                when (item.lookupString) {
                    receiver -> 0 to 0
                    in members.factories -> 0 to 1
                    in members.presets -> 1 to 0
                    else -> null
                }
            }
            valueType != null && isColorClass(valueType) -> runtime.colorMembers(module, binaryName(valueType))?.let { value ->
                valueDocs = { name -> docSummary(file, valueType, name, value.extensionPackages[name]) }
                valueClass = valueType.shortClassName.asString()
                Sections(listOf(PROPERTIES, LINEAR_OPERATIONS, OPERATIONS, QUERY, CONVERT)) { item ->
                    when (item.lookupString) {
                        in value.properties -> 0 to 0
                        in value.operations -> if (item.lookupString in OPERATORS) 1 to OPERATORS.keys.indexOf(item.lookupString) else 2 to 0
                        in value.queries -> 3 to 0
                        in value.conversions -> 4 to 0
                        else -> null
                    }
                }
            }
            // `program.` / `this.` in a program, and plain completion inside `program { }`: Program's own sections
            (valueType != null && binaryName(valueType) in PROGRAM_CLASSES) ||
                (qualified == null && (implicitType?.let(::binaryName) in PROGRAM_CLASSES || (implicitType == null && insideProgram(parameters)))) -> {
                val events = eventProperties(file, PROGRAM_ID)
                valueDocs = { name -> docSummary(file, PROGRAM_ID, name, null) }
                Sections(listOf(EVENTS) + PROGRAM_SECTIONS.map { it.first }) { item ->
                    val name = item.lookupString
                    if (name in events) return@Sections 0 to events.indexOf(name).coerceAtMost(90)
                    PROGRAM_SECTIONS.indexOfFirst { (_, members) -> name in members }.takeIf { it >= 0 }
                        ?.let { 1 + it to PROGRAM_SECTIONS[it].second.indexOf(name) }
                }
            }
            valueType != null && binaryName(valueType) in EVENT_CLASSES -> runtime.eventMembers(module, binaryName(valueType))?.let { members ->
                valueDocs = { name -> docSummary(file, valueType, name, null) }
                // `keyboard.` has events only: no empty Properties heading
                Sections(listOfNotNull(PROPERTIES.takeIf { members.properties.isNotEmpty() }, EVENTS)) { item ->
                    when (item.lookupString) {
                        in members.properties -> 0 to 0
                        in members.events -> (if (members.properties.isEmpty()) 0 else 1) to place(EVENT_ORDER, item.lookupString)
                        else -> null
                    }
                }
            }
            valueType != null && binaryName(valueType) == DRAWER -> runtime.drawerMembers(module)?.let { drawer ->
                valueDocs = { name -> docSummary(file, valueType, name, null) }
                hideUnit = true
                Sections(listOf(DRAWING_PROPERTIES, TRANSFORMS) + DRAWING.map { it.title }) { item ->
                    val name = item.lookupString
                    when {
                        name in drawer.styleProperties || name in DRAWER_SIZE -> 0 to place(STYLE_ORDER, name)
                        name in drawer.matrices || name in TRANSFORM_FUNCTIONS || TRANSFORM_STACKS.matches(name) -> 1 to place(TRANSFORM_ORDER, name)
                        else -> DRAWING.indexOfFirst { it.pattern.matches(name) }.takeIf { it >= 0 }?.let { 2 + it to place(DRAWING[it].order, name) }
                    }
                }
            }
            // Render targets, buffers and textures: the common members by kind, then the rest of the class in Assorted
            bufferSections != null && bufferType != null -> {
                valueDocs = { name -> docSummary(file, bufferType, name, null) }
                hideUnit = true
                Sections(bufferSections.map { it.first } + ASSORTED) { item ->
                    val section = item.getUserData(LIST_SECTION) ?: return@Sections null
                    section to (bufferSections.getOrNull(section)?.second?.indexOf(item.lookupString)?.takeIf { it >= 0 } ?: 90)
                }
            }
            // Any other type with orx-parameters (Parameters) or OPENRNDR `Event<…>` properties (Events): orx types,
            // your own classes
            classType != null && (otherParameters.isNotEmpty() || otherEvents.isNotEmpty()) -> {
                val parameters = otherParameters
                val events = otherEvents
                valueDocs = { name -> docSummary(file, classType, name, null) }
                val titles = listOfNotNull(PARAMETERS.takeIf { parameters.isNotEmpty() }, EVENTS.takeIf { events.isNotEmpty() })
                Sections(titles) { item ->
                    when (item.lookupString) {
                        in parameters -> 0 to parameters.indexOf(item.lookupString).coerceAtMost(90)
                        in events -> titles.indexOf(EVENTS) to events.indexOf(item.lookupString).coerceAtMost(90)
                        else -> null
                    }
                }
            }
            else -> null
        }
        // One sectioned sorter for every item: the list sorts each sorter's items on their own and then joins them,
        // so items keeping different sorters (members vs extensions) would end up in each other's sections
        var sharedSorter: CompletionSorter? = null

        // Headings and constructors (`ColorRGBa(…)`, which Kotlin does not offer after `ColorRGBa.`) go out with the
        // first item of the other contributors: the list pins the rows it shows first, so anything added at the end
        // would land below them whatever its sort order. They share that item's sorter so they sort into place.
        val constructors = if (members != null) {
            constructorSignatures(file, receiver).map { constructorItem(receiver, it, receiverStart) }
        } else if (filters != null) {
            filters.mapNotNull { filterItem(module, it) }
        } else {
            emptyList()
        }
        // In sectioned lists, the items from the other contributors, held until all of them have arrived
        val held = ArrayList<Pair<LookupElement, CompletionResult>>()

        // A section's heading goes out with its first item, so a section without items, like Images after
        // `program.`, gets no heading. While typing, a heading stays as long as one of its items still matches.
        val sectionMembers = HashMap<Int, MutableSet<String>>()
        val headed = HashSet<Int>()
        // Everything in the sections, and everything after them, for the closing line between the two
        val allSectioned: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val rest: MutableSet<String> = ConcurrentHashMap.newKeySet()
        var closed = false
        fun pass(element: LookupElement, matcher: PrefixMatcher, sorter: CompletionSorter): Boolean {
            val wrapped = CompletionResult.wrap(element, matcher, sorter) ?: return false
            val section = sections?.sectionIndex(element)
            if (section != null) {
                val names = sectionMembers.getOrPut(section) { ConcurrentHashMap.newKeySet() }
                names += element.lookupString
                allSectioned += element.lookupString
                // The heading goes out once an item of its section matches what is typed, as the list will show it
                val heading = HeadingMatcher(matcher.prefix, names)
                if (section !in headed && heading.prefixMatches(element.lookupString)) {
                    headed += section
                    CompletionResult.wrap(SeparatorLookupElement(sections.titles[section]), heading, sorter)?.let(result::passResult)
                }
            } else if (sections != null) {
                rest += element.lookupString
            }
            // A closing line after the last section, shown while there are items on both sides of it
            if (sections != null && !closed) {
                val closing = ClosingMatcher(matcher.prefix, allSectioned, rest)
                if (closing.prefixMatches("")) {
                    closed = true
                    CompletionResult.wrap(sections.closing, closing, sorter)?.let(result::passResult)
                }
            }
            result.passResult(wrapped)
            return true
        }

        result.runRemainingContributors(parameters) { completion ->
            val item = completion.lookupElement
            val name = item.lookupString
            // Kotlin's own rows for the listed filters (`BoxBlur()` by expected type) would show them twice
            if (filterNames != null && OpenrndrFilters.classOf(item.psiElement) in filterNames) return@runRemainingContributors
            val isPreset = members?.presets?.contains(name) ?: (receiver != null && CONSTANT.matches(name))
            val color: (() -> Color?)? = when {
                receiver != null && isPreset -> { { constantColor(module, receiver, name) } }
                receiver == null -> variables[name]?.let { property -> { property.initializer?.let { colorProvider()?.colorOf(it) } } }
                else -> null
            }
            // Presets show their swatch, without Kotlin's "for ColorRGBa.Companion in …" note on the orx ones;
            // members of a color value get their KDoc summary instead of "for ColorRGBa in …"
            val docs = valueDocs
            val operatorOf = valueClass
            val decorated = when {
                color != null -> WithSwatch(item, color, clearTail = receiver != null)
                docs != null && operatorOf != null && name in OPERATORS && sections?.contains(item) == true ->
                    AsOperator(item, OPERATORS.getValue(name), operatorOf) { summaryOf(item) { docs(name) } }
                docs != null && inTransformBuilder && isTransformBuilderMember(item) -> WithDocSummary(item, hideUnit = true) { summaryOf(item) { docs(name) } }
                    .also { it.putUserData(TRANSFORM_BUILDER_MEMBER, true) }
                docs != null && inRenderTargetBuilder -> attachmentSection(item)?.let { section ->
                    WithDocSummary(item, hideUnit = true) { summaryOf(item) { docs(name) } }.also { it.putUserData(BUILDER_SECTION, section) }
                } ?: item
                docs != null && bufferSections != null -> bufferSection(item, bufferOwners, bufferSections)?.let { section ->
                    // Picking `post` on a Layer opens the filter list right away
                    val autoPopup = name in OpenrndrFilters.FILTER_CALLS && className in LAYER_CLASSES
                    WithDocSummary(item, hideUnit = true, autoPopup = autoPopup) { summaryOf(item) { docs(name) } }
                        .also { it.putUserData(LIST_SECTION, section) }
                } ?: item
                docs != null && collectionSections != null -> listSection(item, collectionSections)?.let { section ->
                    WithDocSummary(item) { summaryOf(item) { docs(name) } }.also { it.putUserData(LIST_SECTION, section) }
                } ?: item
                docs != null && !inTransformBuilder && !inRenderTargetBuilder && sections?.contains(item) == true -> WithDocSummary(item, hideUnit = hideUnit) { summaryOf(item) { docs(name) } }
                else -> item
            }
            if (sections == null) {
                result.passResult(completion.withLookupElement(decorated))
                return@runRemainingContributors
            }
            // Before the prefix weigher: sections decide the order, and within a section the best matches come first
            if (sharedSorter == null) sharedSorter = completion.sorter.weighBefore("prefix", sections)
            held += decorated to completion
        }

        // Sectioned lists are passed on in one go, once everything has arrived: the list pins the rows it shows
        // first, so items arriving later (Kotlin's extensions after its members) could only go below them, into
        // a later section
        val sorter = sharedSorter
        if (sorter == null) {
            // Nothing came from the other contributors: still offer the constructors
            constructors.forEach(result::addElement)
            return
        }
        constructors.forEach { pass(it, result.prefixMatcher, sorter) }
        for ((decorated, completion) in held) {
            if (!pass(decorated, completion.prefixMatcher, sorter)) result.passResult(completion.withLookupElement(decorated))
        }
    }

    /**
     * Sections of a completion list, with a heading row each, in the order of [titles]. [sectionOf] gives an item's
     * section and its place within it (`0 to 1`: first section, after the items placed 0), or null for items that
     * come after all sections. Runs before prefix matching, so while typing the sections keep their order and the best
     * matches come first within each.
     */
    private class Sections(val titles: List<String>, private val sectionOf: (LookupElement) -> Pair<Int, Int>?) :
        LookupElementWeigher("openrndr.colorSections") {

        /** Whether [element] belongs to one of the sections. */
        fun contains(element: LookupElement) = sectionOf(element) != null

        /** The index in [titles] of [element]'s section, or null. */
        fun sectionIndex(element: LookupElement): Int? = if (element is SeparatorLookupElement) null else sectionOf(element)?.first

        /**
         * The line after the last section, before the items that are in none. Its title is a space: an empty one
         * would match an empty prefix exactly, and the list lifts exact matches to the top.
         */
        val closing = SeparatorLookupElement(" ")

        override fun weigh(element: LookupElement): Int {
            if (element === closing) return titles.size * SLOTS - 1
            if (element is SeparatorLookupElement) return titles.indexOf(element.title) * SLOTS
            val (section, place) = sectionOf(element) ?: return titles.size * SLOTS
            return section * SLOTS + 1 + place
        }

        private companion object {
            const val SLOTS = 100
        }
    }

    /** Shows the closing line while both a sectioned item and an item after the sections match what is typed. */
    private class ClosingMatcher(prefix: String, private val sectioned: Set<String>, private val rest: Set<String>) : PrefixMatcher(prefix) {
        override fun prefixMatches(name: String): Boolean {
            if (prefix.isEmpty()) return sectioned.isNotEmpty() && rest.isNotEmpty()
            val matcher = CamelHumpMatcher(prefix, false)
            return sectioned.any(matcher::isStartMatch) && rest.any(matcher::isStartMatch)
        }

        override fun isStartMatch(name: String) = prefixMatches(name)
        override fun cloneWithPrefix(prefix: String): PrefixMatcher = ClosingMatcher(prefix, sectioned, rest)
    }

    /**
     * Shows a section heading while any of its [members] matches what is typed at a word start (`d` → `drawer`,
     * `bC` → `backgroundColor`), the way the list itself filters, so no heading is left without items.
     */
    private class HeadingMatcher(prefix: String, private val members: Set<String>) : PrefixMatcher(prefix) {
        override fun prefixMatches(name: String): Boolean {
            if (prefix.isEmpty()) return true
            val matcher = CamelHumpMatcher(prefix, false)
            return members.any(matcher::isStartMatch)
        }

        override fun isStartMatch(name: String) = prefixMatches(name)
        override fun cloneWithPrefix(prefix: String): PrefixMatcher = HeadingMatcher(prefix, members)
    }

    /**
     * The JVM class of the color value before the dot (`org.openrndr.color.ColorRGBa` for `ColorRGBa.PINK.` or
     * `myColor.`), when it is one of OPENRNDR's or orx's color classes. The type comes from the original file,
     * where the receiver has the same text range as in the completion copy.
     */
    private fun valueClass(file: KtFile, qualified: KtDotQualifiedExpression): ClassId? {
        val range = qualified.receiverExpression.textRange
        val receiver = PsiTreeUtil.findElementOfClassAtRange(file, range.startOffset, range.endOffset, KtExpression::class.java) ?: return null
        return analyze(receiver) { (receiver.expressionType as? KaClassType)?.classId }
    }

    /** The properties of [classId] (inherited ones included) whose type is OPENRNDR's `Event<…>`, in declaration order. */
    private fun eventProperties(file: KtFile, classId: ClassId): List<String> = analyze(file) {
        val cls = findClass(classId) ?: return@analyze emptyList()
        cls.memberScope.callables
            .filterIsInstance<KaPropertySymbol>()
            .filter { (it.returnType as? KaClassType)?.classId == EVENT_CLASS_ID }
            .map { it.name.asString() }
            .distinct()
            .toList()
    }

    /**
     * The innermost implicit receiver at [offset] that has sections here (TransformBuilder, RenderTargetBuilder, Program,
     * ShadeStyle, VertexFormat, …), from Kotlin's
     * scope: inside `transform { }` nested in `program { }`, that is the TransformBuilder.
     */
    private fun innermostKnownReceiver(file: KtFile, offset: Int): ClassId? {
        val element = file.findElementAt((offset - 1).coerceAtLeast(0)) ?: return null
        val position = PsiTreeUtil.getParentOfType(element, KtElement::class.java, false) ?: return null
        return analyze(position) {
            val receivers = file.scopeContext(position).implicitReceivers.mapNotNull { (it.type as? KaClassType)?.classId }
            val known = receivers.firstOrNull {
                binaryName(it).let { name -> name == TRANSFORM_BUILDER || name == RENDER_TARGET_BUILDER || name in PROGRAM_CLASSES || name in BUFFER_SECTIONS }
            }
            // A layer's sections only directly in its block: not in `draw { }` / `mask { }` (no receiver of their
            // own, where the program's sections apply) or a filter's `post(…) { }` block (the filter is `this`)
            val lambda = PsiTreeUtil.getParentOfType(position, KtLambdaExpression::class.java)
            val lambdaHasReceiver = (lambda?.functionLiteral?.symbol as? KaAnonymousFunctionSymbol)?.receiverParameter != null
            // The receiver of the block itself, when it has sections through a supertype (a filter in `post(BoxBlur()) { }`)
            // or orx-parameters
            val own = receivers.firstOrNull()?.takeIf { lambdaHasReceiver && it != known }?.takeIf { receiver ->
                val cls = findClass(receiver) ?: return@takeIf false
                cls.defaultType.allSupertypes.any { (it as? KaClassType)?.classId?.let(::binaryName) in BUFFER_SECTIONS } ||
                    cls.memberScope.callables.any { symbol -> symbol.annotations.any(::isParameterAnnotation) }
            }
            when {
                own != null -> own
                known != null && binaryName(known) in LAYER_CLASSES && (receivers.firstOrNull() != known || !lambdaHasReceiver) -> null
                else -> known
            }
        }
    }

    /** What sections need to know about a class: its supertypes, and its orx-parameters members in UI order. */
    private class ClassInfo(val supertypes: List<String>, val parameters: List<String>)

    private fun classInfo(file: KtFile, classId: ClassId): ClassInfo? = analyze(file) {
        val cls = findClass(classId) ?: return@analyze null
        val supertypes = cls.defaultType.allSupertypes.mapNotNull { (it as? KaClassType)?.classId?.let(::binaryName) }.toList()
        // By the annotation's `order`, then in declaration order
        val parameters = cls.memberScope.callables.mapNotNull { symbol ->
            val annotation = symbol.annotations.firstOrNull(::isParameterAnnotation) ?: return@mapNotNull null
            val order = (annotation.arguments.firstOrNull { it.name.asString() == "order" }?.expression as? KaAnnotationValue.ConstantValue)
                ?.value?.value as? Int ?: Int.MAX_VALUE
            symbol.callableId?.callableName?.asString()?.let { it to order }
        }.toList().sortedBy { it.second }.map { it.first }.distinct()
        ClassInfo(supertypes, parameters)
    }

    /** `@DoubleParameter`, `@ColorParameter`, `@ActionParameter`, … from orx-parameters; not the class's `@Description`. */
    private fun isParameterAnnotation(annotation: KaAnnotation): Boolean {
        val classId = annotation.classId ?: return false
        return classId.packageFqName.asString() == PARAMETERS_PACKAGE && classId.shortClassName.asString() != "Description"
    }

    /**
     * [sections] with the class's orx-parameters first: in their own Parameters section, or added to the front of
     * an existing one (ShadeStyle's `parameter(…)`).
     */
    private fun withParameters(sections: List<Pair<String, List<String>>>, parameters: List<String>): List<Pair<String, List<String>>> {
        if (parameters.isEmpty()) return sections
        val existing = sections.indexOfFirst { it.first == PARAMETERS }
        if (existing < 0) return listOf(PARAMETERS to parameters) + sections
        return sections.mapIndexed { i, section -> if (i == existing) PARAMETERS to (parameters + section.second) else section }
    }

    /**
     * `post` / `blend` when completion is in the first argument of that call on a Layer: in `compose { }` /
     * `layer { }`, or `layer.post(…)`. Only for a plain name (`post(Bl<caret>)`), not `post(blur.<caret>)`.
     */
    private fun filterCall(file: KtFile, parameters: CompletionParameters, qualified: KtDotQualifiedExpression?): String? {
        val name = (((parameters.position.parent?.parent?.parent as? KtValueArgumentList)?.parent as? KtCallExpression)
            ?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
        return name?.takeIf { it in OpenrndrFilters.FILTER_CALLS && isFilterArgument(file, parameters, qualified, it) }
    }

    private fun isFilterArgument(file: KtFile, parameters: CompletionParameters, qualified: KtDotQualifiedExpression?, callName: String): Boolean {
        if (qualified != null) return false
        val reference = parameters.position.parent as? KtNameReferenceExpression ?: return false
        val argument = reference.parent as? KtValueArgument ?: return false
        val list = argument.parent as? KtValueArgumentList ?: return false
        if (list.arguments.firstOrNull() != argument) return false
        val call = list.parent as? KtCallExpression ?: return false
        if ((call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() != callName) return false
        val onValue = call.parent as? KtDotQualifiedExpression
        if (onValue != null && onValue.selectorExpression == call) {
            val range = onValue.receiverExpression.textRange
            val target = PsiTreeUtil.findElementOfClassAtRange(file, range.startOffset, range.endOffset, KtExpression::class.java) ?: return false
            return analyze(target) { (target.expressionType as? KaClassType)?.classId?.let(::binaryName) in LAYER_CLASSES }
        }
        // Implicit receiver: any Layer in scope (the call itself resolves to Layer.post then)
        val element = file.findElementAt((parameters.offset - 1).coerceAtLeast(0)) ?: return false
        val position = PsiTreeUtil.getParentOfType(element, KtElement::class.java, false) ?: return false
        return analyze(position) {
            file.scopeContext(position).implicitReceivers.any { (it.type as? KaClassType)?.classId?.let(::binaryName) in LAYER_CLASSES }
        }
    }

    /**
     * A filter for `post(…)`: `BoxBlur()` with its package and KDoc summary. Picking it writes `BoxBlur()` (the
     * caret between the parentheses when it needs arguments) and imports the class.
     */
    private fun filterItem(module: Module, filter: OpenrndrFilters.FilterClass): LookupElement? {
        val cls = JavaPsiFacade.getInstance(module.project)
            .findClass(filter.qualifiedName, module.getModuleWithDependenciesAndLibrariesScope(false)) ?: return null
        val item = LookupElementBuilder.create(cls, filter.name)
            .withIcon(AllIcons.Nodes.Class)
            .withTailText(if (filter.needsArguments) "(…)" else "()", true)
            .withTypeText(filter.packageName, true)
            .withInsertHandler { context, _ ->
                val document = context.document
                val start = context.startOffset
                val text = "${filter.qualifiedName}()"
                document.replaceString(start, context.tailOffset, text)
                val inserted = document.createRangeMarker(start, start + text.length)
                context.commitDocument()
                (context.file as? KtFile)?.let { ShortenReferencesFacility.getInstance().shorten(it, inserted.textRange) }
                PsiDocumentManager.getInstance(context.project).doPostponedOperationsAndUnblockDocument(document)
                context.editor.caretModel.moveToOffset(if (filter.needsArguments) inserted.endOffset - 1 else inserted.endOffset)
                if (filter.needsArguments) AutoPopupController.getInstance(context.project).autoPopupParameterInfo(context.editor, null)
                inserted.dispose()
            }
        return WithDocSummary(item) { summaryOf(item) { null } }.also { it.putUserData(FILTER_CLASS, filter) }
    }

    /**
     * The List section of [item]: its curated section by name, or Assorted, when it is declared on a collection (a
     * member of a `kotlin.collections` / `java.util` class, or an extension on List, Collection, Iterable, …).
     * Null for anything else (`let`, `apply`, `toString`), which goes after the sections.
     */
    private fun listSection(item: LookupElement, sections: List<Pair<String, List<String>>>): Int? {
        val declaration = item.psiElement?.navigationElement ?: return null
        val onCollection = when (declaration) {
            is KtCallableDeclaration -> {
                val owner = declaration.containingClassOrObject?.fqName?.asString()
                val receiver = declaration.receiverTypeReference?.typeElement?.let { type ->
                    (type as? KtUserType ?: (type as? KtNullableType)?.innerType as? KtUserType)?.referencedName
                }
                // Extensions in kotlin.collections / kotlin.text count too, also with a type parameter receiver (`C.ifEmpty`)
                val inCollectionsPackage = (declaration.containingFile as? KtFile)?.packageFqName?.asString() in setOf("kotlin.collections", "kotlin.text")
                owner?.let { it.startsWith("kotlin.collections.") || it.startsWith("java.util.") || it in STRING_OWNERS } == true ||
                    receiver in COLLECTION_TYPES || (inCollectionsPackage && declaration.receiverTypeReference != null)
            }
            is PsiMember -> declaration.containingClass?.qualifiedName?.let {
                it.startsWith("java.util.") || it.startsWith("java.lang.Iterable") || it.startsWith("kotlin.collections.") || it in STRING_OWNERS
            } == true
            else -> false
        }
        if (!onCollection) return null
        // Extensions on a list or iterable of vectors (`List<Vector2>.bounds`, `Iterable<Vector3>.average`) are Spatial
        if (isOnVectors(declaration)) return 0
        val curated = sections.indexOfFirst { (_, names) -> item.lookupString in names }
        return 1 + (if (curated >= 0) curated else sections.size)
    }

    /**
     * The section of [item] after a value of [className] (a render target, buffer, texture, shader, …): its curated
     * section by name, or Assorted, when it is declared in one of [owners] (the class and its supertypes), as an
     * extension on one of them, or is AutoCloseable's `close` / Kotlin's `use`; null for everything else (`equals`,
     * `let`, … also when the class overrides them).
     */
    private fun bufferSection(item: LookupElement, owners: Set<String>, sections: List<Pair<String, List<String>>>): Int? {
        val declaration = item.psiElement?.navigationElement ?: return null
        val name = item.lookupString
        if (name in ANY_MEMBERS) return null
        val closable = sections.any { it.first == LIFECYCLE.first }
        val own = when (declaration) {
            is KtCallableDeclaration -> {
                val owner = declaration.containingClassOrObject?.fqName?.asString()
                val receiver = declaration.receiverTypeReference?.typeElement?.let { type ->
                    (type as? KtUserType ?: (type as? KtNullableType)?.innerType as? KtUserType)?.referencedName
                }
                val inKotlin = (declaration.containingFile as? KtFile)?.packageFqName?.asString()?.startsWith("kotlin") == true
                owner in owners || owners.any { receiver == it.substringAfterLast('.') } ||
                    (name in CLOSING && closable && (owner == "kotlin.AutoCloseable" || inKotlin))
            }
            is PsiMember -> name in CLOSING && closable && declaration.containingClass?.qualifiedName == "java.lang.AutoCloseable"
            else -> false
        }
        if (!own) return null
        val curated = sections.indexOfFirst { (_, names) -> name in names }
        return if (curated >= 0) curated else sections.size
    }

    /** Whether [declaration] extends a collection of vectors: `List<Vector2>`, `Iterable<Vector3>`, … */
    private fun isOnVectors(declaration: PsiElement): Boolean {
        val type = (declaration as? KtCallableDeclaration)?.receiverTypeReference?.typeElement as? KtUserType ?: return false
        return type.typeArgumentsAsTypes.any { argument ->
            (argument?.typeElement as? KtUserType)?.referencedName in VECTOR_NAMES
        }
    }

    /**
     * The RenderTargetBuilder section of [item]: 0 (Attach New) for an attachment it creates (`colorBuffer(format)`,
     * `depthBuffer()`), 1 (Attach Existing) for one that takes an existing buffer or texture (`colorBuffer(cb)`,
     * `cubemap(…)`); null when not declared in RenderTargetBuilder, or deprecated (the `width, height` colorBuffer).
     */
    private fun attachmentSection(item: LookupElement): Int? {
        val declaration = item.psiElement?.navigationElement as? KtCallableDeclaration ?: return null
        if (declaration.containingClassOrObject?.fqName?.asString() != RENDER_TARGET_BUILDER) return null
        if (declaration.annotationEntries.any { it.shortName?.asString() == "Deprecated" }) return null
        val existing = declaration.valueParameters.any { parameter ->
            parameter.typeReference?.text?.substringAfterLast('.')?.trim() in ATTACHABLES
        }
        return if (existing) 1 else 0
    }

    /** Whether [item] is declared in TransformBuilder or as an extension on it, not just named like one of its members. */
    private fun isTransformBuilderMember(item: LookupElement): Boolean {
        val declaration = item.psiElement?.navigationElement as? KtCallableDeclaration ?: return false
        if (declaration.containingClassOrObject?.fqName?.asString() == TRANSFORM_BUILDER) return true
        return declaration.receiverTypeReference?.text?.substringAfterLast('.')?.trim() == TRANSFORM_BUILDER.substringAfterLast('.')
    }

    /** Whether the caret is inside a `program { }` block (its `extend { }` blocks too), where `this` is the Program. */
    private fun insideProgram(parameters: CompletionParameters): Boolean =
        generateSequence(parameters.position.parent) { it.parent }
            .filterIsInstance<KtLambdaExpression>()
            .any { lambda ->
                val call = (lambda.parent as? KtLambdaArgument)?.parent as? KtCallExpression
                (call?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() == "program"
            }

    private fun isColorClass(classId: ClassId) = OpenrndrColorEvaluator.COLOR_PACKAGES.any { binaryName(classId).startsWith("$it.") }

    /**
     * The first sentence of the KDoc of member [name] of [classId], or of the extension with that name in
     * [extensionPackage]. Needs the library's sources (which IntelliJ downloads for Gradle projects); null without.
     */
    private fun docSummary(file: KtFile, classId: ClassId, name: String, extensionPackage: String?): String? {
        val key = "${file.project.locationHash}|$classId|$name"
        // Analysis is not allowed on the UI thread; the list renders these in the background
        if (ApplicationManager.getApplication().isDispatchThread) return DOC_SUMMARIES[key]?.orElse(null)
        return DOC_SUMMARIES.getOrPut(key) {
            val summary = analyze(file) {
                val identifier = Name.identifier(name)
                val members = findClass(classId)?.memberScope?.callables(identifier)?.toList().orEmpty()
                val extensions = extensionPackage?.let { findTopLevelCallables(FqName(it), identifier).toList() }.orEmpty()
                (members + extensions).firstNotNullOfOrNull { symbol ->
                    (symbol.psi?.navigationElement as? KtDeclaration)?.docComment?.getDefaultSection()?.getContent()?.let(::firstSentence)
                }
            }
            Optional.ofNullable(summary)
        }.orElse(null)
    }

    /**
     * The first sentence of the KDoc of the declaration behind [item], so each overload gets its own; [byName]
     * (the first documented callable with the item's name) only for items without a declaration.
     */
    private fun summaryOf(item: LookupElement, byName: () -> String?): String? {
        val element = item.psiElement ?: return byName()
        val key = element.containingFile?.virtualFile?.let { "${element.project.locationHash}|${it.path}|${element.textOffset}" }
            ?: return byName()
        // Finding a library's sources can be slow; the list renders these in the background
        if (ApplicationManager.getApplication().isDispatchThread) return DOC_SUMMARIES[key]?.orElse(null)
        return DOC_SUMMARIES.getOrPut(key) {
            val declaration = element.navigationElement as? KtDeclaration ?: element as? KtDeclaration
            Optional.ofNullable(declaration?.docComment?.getDefaultSection()?.getContent()?.let(::firstSentence))
        }.orElse(null)
    }

    /** `Returns a copy with the alpha scaled.` from a KDoc text, without link brackets or backticks, at most [MAX_SUMMARY] characters. */
    private fun firstSentence(kdoc: String): String? {
        val text = kdoc.replace(Regex("""\[([^\]]+)]"""), "$1").replace("`", "").replace(Regex("""\s+"""), " ").trim()
        if (text.isEmpty()) return null
        val sentence = Regex("""^(.+?[.!?])(\s|$)""").find(text)?.groupValues?.get(1) ?: text
        return if (sentence.length > MAX_SUMMARY) sentence.take(MAX_SUMMARY - 1).trimEnd() + "…" else sentence
    }

    /** The public constructors of `org.openrndr.color.<receiver>`, as parameter lists: `(r: Double, …, alpha: Double = …)`. */
    private fun constructorSignatures(file: KtFile, receiver: String): List<String> = analyze(file) {
        val cls = findClass(ClassId.topLevel(FqName("org.openrndr.color.$receiver"))) ?: return@analyze emptyList()
        cls.declaredMemberScope.constructors
            .filter { it.visibility == KaSymbolVisibility.PUBLIC }
            .map { constructor ->
                constructor.valueParameters.joinToString(", ", "(", ")") { parameter ->
                    val type = (parameter.returnType as? KaClassType)?.classId?.shortClassName?.asString() ?: "?"
                    val nullable = if (parameter.returnType.isMarkedNullable) "?" else ""
                    "${parameter.name.asString()}: $type$nullable" + if (parameter.hasDefaultValue) " = …" else ""
                }
            }
            .toList()
    }

    /** `ColorRGBa(r: Double, …)`; picking it turns `ColorRGBa.` into `ColorRGBa(<caret>)` and shows parameter info. */
    private fun constructorItem(receiver: String, signature: String, receiverStart: Int): LookupElement =
        LookupElementBuilder.create(receiver)
            .withTailText(signature, true)
            .withTypeText("constructor")
            .withIcon(AllIcons.Nodes.Class)
            .withInsertHandler { context, _ ->
                context.document.replaceString(receiverStart, context.tailOffset, "$receiver()")
                context.editor.caretModel.moveToOffset(receiverStart + receiver.length + 1)
                context.commitDocument()
                AutoPopupController.getInstance(context.project).autoPopupParameterInfo(context.editor, null)
            }

    /** `ColorRGBa` when completing `ColorRGBa.<caret>` (any `Color…` class), else null. */
    private fun colorReceiver(parameters: CompletionParameters): String? {
        val reference = parameters.position.parent as? KtNameReferenceExpression ?: return null
        val qualified = reference.parent as? KtDotQualifiedExpression ?: return null
        if (qualified.selectorExpression != reference) return null
        val name = (qualified.receiverExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        return name.takeIf { it.startsWith("Color") }
    }

    /** A named color: a companion member (`ColorRGBa.RED`) or, for ColorRGBa, an orx preset (`ColorRGBa.ORANGE_RED`). */
    private fun constantColor(module: Module, receiver: String, name: String): Color? {
        val runtime = OpenrndrColorRuntime.getInstance(module.project)
        val companion = ColorArg.StaticField("org.openrndr.color.$receiver", "Companion")
        val getter = "get$name"
        val member = ColorCall.Invoke("org.openrndr.color", "org.openrndr.color.$receiver\$Companion", getter, companion, emptyList(), emptyList())
        val value = runtime.evaluate(module, member)
            ?: if (receiver == "ColorRGBa") {
                val preset = ColorCall.Invoke(
                    "org.openrndr.extra.color.presets", null, getter, null,
                    listOf("org.openrndr.color.ColorRGBa\$Companion"), listOf(companion),
                )
                runtime.evaluate(module, preset)
            } else {
                null
            }
        return value?.let { runtime.toAwt(module, it) }
    }

    /**
     * [item] with a color swatch as its icon, computed when it is shown. With [clearTail], the item's tail text
     * (Kotlin's "for ColorRGBa.Companion in org.openrndr.extra.color.presets" on extension properties) is left out.
     */
    private class WithSwatch(item: LookupElement, private val color: () -> Color?, private val clearTail: Boolean) :
        LookupElementDecorator<LookupElement>(item) {

        override fun renderElement(presentation: LookupElementPresentation) {
            super.renderElement(presentation)
            if (clearTail) presentation.clearTail()
        }

        override fun getExpensiveRenderer(): LookupElementRenderer<out LookupElement> = object : LookupElementRenderer<LookupElement>() {
            override fun renderElement(element: LookupElement, presentation: LookupElementPresentation) {
                // Keep whatever the item already renders lazily (Kotlin adds type details this way)
                @Suppress("UNCHECKED_CAST")
                val original = delegate.expensiveRenderer as LookupElementRenderer<LookupElement>?
                if (original != null) original.renderElement(delegate, presentation) else delegate.renderElement(presentation)
                if (clearTail) presentation.clearTail()
                color()?.let { presentation.icon = JBUIScale.scaleIcon(ColorIcon(12, it, true)) }
            }
        }
    }

    /**
     * [item] without Kotlin's "for ColorRGBa in org.openrndr…" note (on extensions), and with the first sentence of
     * its documentation in grey after the parameters, computed in the background when the item is shown.
     */
    private class WithDocSummary(
        item: LookupElement,
        private val hideUnit: Boolean = false,
        /** Opens completion again after inserting: `post(` / `blend(` then lists the filters. */
        private val autoPopup: Boolean = false,
        private val summary: () -> String?,
    ) : LookupElementDecorator<LookupElement>(item) {

        override fun handleInsert(context: InsertionContext) {
            super.handleInsert(context)
            if (autoPopup) AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
        }

        override fun renderElement(presentation: LookupElementPresentation) {
            super.renderElement(presentation)
            dropReceiverNote(presentation)
            dropUnit(presentation)
        }

        /** With [hideUnit], no `Unit` return type on the right (TransformBuilder's translate, rotate, …). */
        private fun dropUnit(presentation: LookupElementPresentation) {
            if (hideUnit && presentation.typeText == "Unit") presentation.setTypeText(null)
        }

        override fun getExpensiveRenderer(): LookupElementRenderer<out LookupElement> = object : LookupElementRenderer<LookupElement>() {
            override fun renderElement(element: LookupElement, presentation: LookupElementPresentation) {
                @Suppress("UNCHECKED_CAST")
                val original = delegate.expensiveRenderer as LookupElementRenderer<LookupElement>?
                if (original != null) original.renderElement(delegate, presentation) else delegate.renderElement(presentation)
                dropReceiverNote(presentation)
                dropUnit(presentation)
                summary()?.let { presentation.appendTailText("  $it", true) }
            }
        }

        /** Keeps the tail up to Kotlin's " for <Receiver> in <package>" note on extension members. */
        private fun dropReceiverNote(presentation: LookupElementPresentation) {
            val fragments = presentation.tailFragments.toList()
            val cut = fragments.indexOfFirst { RECEIVER_NOTE.containsMatchIn(it.text) }
            if (cut < 0) return
            presentation.clearTail()
            fragments.take(cut).forEach { presentation.appendTailText(it.text, it.isGrayed) }
            val before = RECEIVER_NOTE.find(fragments[cut].text)!!.range.first
            if (before > 0) presentation.appendTailText(fragments[cut].text.substring(0, before), fragments[cut].isGrayed)
        }
    }

    /**
     * An operator function shown as the operator: `plus(other: ColorRGBa)` as `ColorRGBa + ColorRGBa`, with the
     * first sentence of its KDoc. Picking it writes the operator: `ColorRGBa.PINK.` + plus gives `ColorRGBa.PINK + `.
     */
    private class AsOperator(item: LookupElement, private val symbol: String, private val receiver: String, private val summary: () -> String?) :
        LookupElementDecorator<LookupElement>(item) {

        override fun renderElement(presentation: LookupElementPresentation) {
            super.renderElement(presentation)
            asOperator(presentation)
        }

        override fun getExpensiveRenderer(): LookupElementRenderer<out LookupElement> = object : LookupElementRenderer<LookupElement>() {
            override fun renderElement(element: LookupElement, presentation: LookupElementPresentation) {
                @Suppress("UNCHECKED_CAST")
                val original = delegate.expensiveRenderer as LookupElementRenderer<LookupElement>?
                if (original != null) original.renderElement(delegate, presentation) else delegate.renderElement(presentation)
                asOperator(presentation)
                summary()?.let { presentation.appendTailText("  $it", true) }
            }
        }

        /** `ColorRGBa + ColorRGBa` from the item's parameter list `(other: ColorRGBa)`; `-ColorRGBa` without one. */
        private fun asOperator(presentation: LookupElementPresentation) {
            val tail = presentation.tailFragments.joinToString("") { it.text }
            if (!tail.startsWith("(")) return
            val operand = PARAMETER_TYPE.find(tail)?.groupValues?.get(1)?.trim()
            presentation.itemText = if (operand == null) "$symbol$receiver" else "$receiver $symbol $operand"
            presentation.clearTail()
        }

        override fun handleInsert(context: InsertionContext) {
            // Replace `.plus` (the dot and what was inserted after it) with ` + `, or `-` before the receiver
            val document = context.document
            val dot = context.startOffset - 1
            if (dot < 0 || document.charsSequence[dot] != '.') return super.handleInsert(context)
            if (symbol == "-" && delegate.lookupString == "unaryMinus") {
                document.deleteString(dot, context.tailOffset)
                return
            }
            document.replaceString(dot, context.tailOffset, " $symbol ")
            context.editor.caretModel.moveToOffset(dot + symbol.length + 2)
        }
    }

    private companion object {
        /** Kotlin operator functions and the operator they are written as, in section order. */
        val OPERATORS = linkedMapOf("plus" to "+", "minus" to "-", "times" to "*", "div" to "/", "rem" to "%", "unaryMinus" to "-")

        /** The type of the first parameter in `(other: ColorRGBa)` or `(scale: Double = …)`. */
        val PARAMETER_TYPE = Regex("""^\(\s*\w+\s*:\s*([^,)=]+)""")

        /** Kotlin's note on extension members: " for ColorRGBa in org.openrndr.extra.color.tools", " for MutableCollection<in T> in kotlin.collections",
         * " for MutableList<Int> in <root>" (the default package). */
        val RECEIVER_NOTE = Regex("""\s*\bfor\s+.+?\s+in\s+(<root>|[\w.]+)\s*$""")
        const val MAX_SUMMARY = 70
        val DOC_SUMMARIES = java.util.concurrent.ConcurrentHashMap<String, Optional<String>>()

        fun binaryName(classId: ClassId) = classId.packageFqName.asString() + "." + classId.relativeClassName.asString().replace('.', '$')

        const val CONSTRUCTORS = "Constructors and factories"
        const val PRESETS = "Color presets"
        const val DRAWER = "org.openrndr.draw.Drawer"

        /** Receiver types of the collection extensions that belong in the collection sections. */
        val COLLECTION_TYPES = setOf(
            "List", "MutableList", "Collection", "MutableCollection", "Iterable", "MutableIterable", "ArrayList",
            "Set", "MutableSet", "HashSet", "LinkedHashSet", "Map", "MutableMap", "HashMap", "LinkedHashMap",
            "String", "CharSequence",
        )

        /** Classes whose members belong in the String sections. */
        val STRING_OWNERS = setOf("kotlin.String", "kotlin.CharSequence", "java.lang.String", "java.lang.CharSequence")

        /** The common List functions by section, in order; every other collection function goes to Assorted. */
        val LIST_SECTIONS = listOf(
            "Read" to listOf("get", "first", "last", "firstOrNull", "lastOrNull", "getOrNull", "getOrElse", "single", "singleOrNull", "subList", "slice", "elementAt"),
            "Write" to listOf("add", "addAll", "set", "addFirst", "addLast", "fill", "plusAssign"),
            "Remove" to listOf(
                "remove", "removeAt", "removeAll", "removeIf", "removeFirst", "removeLast", "removeFirstOrNull", "removeLastOrNull",
                "retainAll", "clear", "minusAssign",
            ),
            "Sort" to listOf(
                "sort", "sorted", "sortBy", "sortedBy", "sortDescending", "sortedDescending", "sortByDescending", "sortedByDescending",
                "sortWith", "sortedWith", "reverse", "reversed", "asReversed",
            ),
            "Query" to listOf(
                "size", "isEmpty", "isNotEmpty", "count", "any", "all", "none", "minOrNull", "maxOrNull", "minBy", "maxBy",
                "minOf", "maxOf", "sum", "sumOf", "average", "indices", "lastIndex",
            ),
            "Search" to listOf(
                "contains", "containsAll", "indexOf", "lastIndexOf", "indexOfFirst", "indexOfLast", "find", "findLast", "binarySearch",
            ),
            "Filter" to listOf(
                "filter", "filterNot", "filterIndexed", "filterIsInstance", "filterNotNull", "take", "takeWhile", "takeLast",
                "drop", "dropWhile", "dropLast", "distinct", "distinctBy", "partition", "chunked", "windowed",
            ),
            "Map" to listOf(
                "map", "mapIndexed", "mapNotNull", "flatMap", "flatten", "forEach", "forEachIndexed", "associate", "associateBy",
                "associateWith", "groupBy", "zip", "zipWithNext", "fold", "reduce", "joinToString", "withIndex", "onEach",
            ),
            "Random" to listOf("random", "randomOrNull", "shuffled", "shuffle"),
        )
        /** The common Set functions by section, in order. */
        val SET_SECTIONS = listOf(
            "Read" to listOf("first", "last", "firstOrNull", "lastOrNull", "single", "singleOrNull", "elementAt"),
            "Write" to listOf("add", "addAll", "plusAssign"),
            "Remove" to listOf("remove", "removeAll", "removeIf", "retainAll", "clear", "minusAssign"),
            "Sort" to listOf("sorted", "sortedBy", "sortedDescending", "sortedByDescending", "sortedWith", "reversed"),
            "Query" to listOf(
                "size", "isEmpty", "isNotEmpty", "count", "any", "all", "none", "minOrNull", "maxOrNull", "minBy", "maxBy",
                "minOf", "maxOf", "sum", "sumOf", "average",
            ),
            "Search" to listOf("contains", "containsAll", "find", "findLast", "indexOf"),
            "Filter" to listOf(
                "filter", "filterNot", "filterIsInstance", "filterNotNull", "take", "drop", "partition", "intersect", "subtract", "union",
            ),
            "Map" to listOf(
                "map", "mapNotNull", "flatMap", "forEach", "associate", "associateBy", "associateWith", "groupBy", "fold", "reduce",
                "joinToString",
            ),
            "Random" to listOf("random", "randomOrNull", "shuffled"),
        )

        /** The common Map functions by section, in order. */
        val MAP_SECTIONS = listOf(
            "Read" to listOf("get", "getValue", "getOrDefault", "getOrElse", "keys", "values", "entries"),
            "Write" to listOf("put", "putAll", "set", "getOrPut", "putIfAbsent", "plusAssign", "merge", "compute", "computeIfAbsent"),
            "Remove" to listOf("remove", "clear", "minusAssign"),
            "Sort" to listOf("toSortedMap"),
            "Query" to listOf("size", "isEmpty", "isNotEmpty", "count", "any", "all", "none", "maxBy", "minBy", "maxOf", "minOf"),
            "Search" to listOf("containsKey", "containsValue", "contains"),
            "Filter" to listOf("filter", "filterNot", "filterKeys", "filterValues"),
            "Map" to listOf("map", "mapKeys", "mapValues", "mapNotNull", "flatMap", "forEach", "toList", "toMutableMap"),
        )

        /** The common String functions by section, in order; String is immutable, so there is no Write or Remove. */
        val STRING_SECTIONS = listOf(
            "Read" to listOf("get", "first", "last", "firstOrNull", "lastOrNull", "getOrNull", "getOrElse", "single", "substring", "slice", "elementAt"),
            "Write" to emptyList(),
            "Remove" to emptyList(),
            "Sort" to listOf("reversed"),
            "Query" to listOf("length", "isEmpty", "isNotEmpty", "isBlank", "isNotBlank", "count", "any", "all", "none", "lastIndex", "indices"),
            "Search" to listOf("contains", "startsWith", "endsWith", "indexOf", "lastIndexOf", "indexOfFirst", "indexOfLast", "find", "matches"),
            "Filter" to listOf(
                "trim", "trimStart", "trimEnd", "removePrefix", "removeSuffix", "removeSurrounding", "substringBefore", "substringAfter",
                "substringBeforeLast", "substringAfterLast", "split", "lines", "take", "takeLast", "takeWhile", "drop", "dropLast",
                "dropWhile", "filter", "filterNot", "chunked", "windowed",
            ),
            "Map" to listOf(
                "uppercase", "lowercase", "replace", "replaceFirst", "replaceFirstChar", "padStart", "padEnd", "repeat", "format", "plus",
                "map", "mapIndexed", "forEach",
            ),
            "Convert" to listOf(
                "toInt", "toIntOrNull", "toDouble", "toDoubleOrNull", "toFloat", "toFloatOrNull", "toLong", "toLongOrNull",
                "toBoolean", "toBooleanStrictOrNull", "toCharArray", "toByteArray", "toList", "toRegex",
            ),
            "Random" to listOf("random", "randomOrNull"),
        )

        /** The sections for each collection value type. */
        val COLLECTION_SECTIONS: Map<String, List<Pair<String, List<String>>>> =
            listOf("kotlin.collections.List", "kotlin.collections.MutableList", "kotlin.collections.ArrayList", "java.util.ArrayList")
                .associateWith { LIST_SECTIONS } +
                listOf(
                    "kotlin.collections.Set", "kotlin.collections.MutableSet", "kotlin.collections.HashSet", "kotlin.collections.LinkedHashSet",
                    "java.util.HashSet", "java.util.LinkedHashSet",
                ).associateWith { SET_SECTIONS } +
                listOf(
                    "kotlin.collections.Map", "kotlin.collections.MutableMap", "kotlin.collections.HashMap", "kotlin.collections.LinkedHashMap",
                    "java.util.HashMap", "java.util.LinkedHashMap",
                ).associateWith { MAP_SECTIONS } +
                mapOf("kotlin.String" to STRING_SECTIONS)

        const val ASSORTED = "Assorted"

        /** First in the collection lists: extensions on lists of vectors, `bounds` first. */
        const val SPATIAL = "Spatial"
        val SPATIAL_ORDER = listOf("bounds", "average", "sum", "map", "delaunayTriangulation", "voronoiDiagram")
        val VECTOR_NAMES = setOf("Vector2", "Vector3", "Vector4")

        val VECTOR_CLASSES = setOf("org.openrndr.math.Vector2", "org.openrndr.math.Vector3", "org.openrndr.math.Vector4")
        const val GEOMETRY = "Geometry"
        const val SWIZZLE = "Swizzle"
        val GEOMETRY_ORDER = listOf("normalized", "perpendicular", "rotate", "mix", "reflect", "reflectedOver", "projectedOn", "cross")
        val VECTOR_QUERY_ORDER = listOf("length", "squaredLength", "dot", "distanceTo", "squaredDistanceTo", "atan2")
        val LIST_SECTION = Key.create<Int>("openrndr.listSection")

        /** AutoCloseable's `close` and Kotlin's `use`, shown with `destroy`. */
        val CLOSING = setOf("close", "use")
        val LIFECYCLE = "Lifecycle" to listOf("destroy", "close", "use")
        private val TEXTURE_SAMPLING = listOf("filterMin", "filterMag", "filter", "wrapU", "wrapV", "anisotropy", "flipV", "generateMipmaps")

        val ANY_MEMBERS = setOf("equals", "hashCode", "toString")
        const val PARAMETERS = "Parameters"
        const val PARAMETERS_PACKAGE = "org.openrndr.extra.parameters"
        const val LAYER = "org.openrndr.extra.compositor.Layer"
        const val COMPOSITE = "org.openrndr.extra.compositor.Composite"
        val LAYER_CLASSES = setOf(LAYER, COMPOSITE)
        const val BLEND_MODES = "Blend Modes"
        const val FILTER_2TO1 = "Filter2to1"
        val FILTER_CLASS = Key.create<OpenrndrFilters.FilterClass>("openrndr.filterClass")
        private val LAYER_SECTIONS = listOf(
            "Draw" to listOf("draw", "mask"),
            "Layers" to listOf("layer", "aside"),
            "Filters" to listOf("post", "blend", "apply"),
            "Properties" to listOf("enabled", "clearColor", "colorType", "invertMask", "maskLayer", "result", "accumulation", "children"),
        )
        private val STYLE_SUPERTYPES = setOf(
            "org.openrndr.draw.StyleParameters", "org.openrndr.draw.StyleBufferBindings", "org.openrndr.draw.StyleImageBindings",
        )
        private val STYLE_PARAMETERS = "Parameters" to listOf("parameter", "parameterValues", "parameterTypes", "parameterType")
        private val STYLE_BUFFERS = "Buffers" to listOf("buffer", "attributes", "buffers", "bufferValues", "bufferTypes", "bufferFlags", "bufferAccess")
        private val STYLE_IMAGES = "Images" to listOf("image", "imageBindings", "imageValues", "imageTypes", "imageAccess", "imageFlags", "imageArrayLength")

        /** The interfaces whose members count as the class's own in [BUFFER_SECTIONS] (Shader's uniforms, ShadeStyle's parameters). */
        val BUFFER_SUPERTYPES: Map<String, Set<String>> = mapOf(
            "org.openrndr.draw.Shader" to setOf(
                "org.openrndr.draw.ShaderUniforms", "org.openrndr.draw.ShaderImageBindings",
                "org.openrndr.draw.ShaderTextureBindings", "org.openrndr.draw.ShaderBufferBindings",
            ),
            "org.openrndr.draw.ShadeStyle" to STYLE_SUPERTYPES,
            "org.openrndr.draw.ComputeStyle" to STYLE_SUPERTYPES,
            COMPOSITE to setOf(LAYER),
        )

        /**
         * Sections after a render target, buffer, texture, shader, shade style or vertex format, by class; everything
         * else of the class goes to Assorted.
         */
        val BUFFER_SECTIONS: Map<String, List<Pair<String, List<String>>>> = mapOf(
            "org.openrndr.draw.RenderTarget" to listOf(
                "Properties" to listOf(
                    "width", "height", "contentScale", "effectiveWidth", "effectiveHeight", "pixelWidth", "pixelHeight", "multisample",
                    "hasColorAttachments", "hasDepthBuffer", "hasStencilBuffer",
                ),
                "Attachments" to listOf("colorBuffer", "colorAttachments", "colorAttachmentByName", "colorAttachmentIndexByName", "depthBuffer"),
                "Attach" to listOf("attach", "attachLayered", "detachColorBuffers", "detachColorAttachments", "detachDepthBuffer"),
                "Clear" to listOf("clearColor", "clearDepth"),
                "Blend Modes" to listOf("blendModes", "setBlendMode", "defaultBlendMode"),
                "Bind" to listOf("bind", "unbind", "resolveTo"),
                LIFECYCLE,
            ),
            "org.openrndr.draw.ColorBuffer" to listOf(
                "Properties" to listOf(
                    "width", "height", "effectiveWidth", "effectiveHeight", "bounds", "contentScale", "format", "type", "levels",
                    "multisample", "dimensionsInPixels",
                ),
                "Sampling" to TEXTURE_SAMPLING,
                "Write" to listOf("fill", "write"),
                "Read" to listOf("shadow", "read", "bufferSize"),
                "Copy" to listOf("copyTo", "crop", "createEquivalent", "isEquivalentTo"),
                "Save" to listOf("saveToFile", "toDataUrl"),
                LIFECYCLE,
            ),
            "org.openrndr.draw.DepthBuffer" to listOf(
                "Properties" to listOf("width", "height", "format", "multisample", "hasDepth", "hasStencil"),
                "Copy" to listOf("copyTo", "resolveTo"),
                LIFECYCLE,
            ),
            "org.openrndr.draw.VertexBuffer" to listOf(
                "Properties" to listOf("vertexFormat", "vertexCount"),
                "Write" to listOf("put", "write"),
                "Read" to listOf("shadow", "read"),
                LIFECYCLE,
            ),
            "org.openrndr.draw.IndexBuffer" to listOf(
                "Properties" to listOf("indexCount", "type"),
                "Write" to listOf("write"),
                "Read" to listOf("shadow", "read"),
                LIFECYCLE,
            ),
            "org.openrndr.draw.ArrayTexture" to listOf(
                "Properties" to listOf("width", "height", "layers", "bounds", "format", "type", "levels"),
                "Sampling" to TEXTURE_SAMPLING,
                "Write" to listOf("fill", "write"),
                "Read" to listOf("read"),
                "Copy" to listOf("copyTo"),
                LIFECYCLE,
            ),
            "org.openrndr.draw.Cubemap" to listOf(
                "Properties" to listOf("width", "format", "type", "levels"),
                "Sampling" to TEXTURE_SAMPLING,
                "Write" to listOf("write"),
                "Read" to listOf("read"),
                "Copy" to listOf("copyTo"),
                LIFECYCLE,
            ),
            "org.openrndr.draw.ArrayCubemap" to listOf(
                "Properties" to listOf("width", "layers", "bounds", "format", "type", "levels"),
                "Sampling" to TEXTURE_SAMPLING,
                "Write" to listOf("write"),
                "Read" to listOf("read"),
                "Copy" to listOf("copyTo"),
                LIFECYCLE,
            ),
            "org.openrndr.draw.Shader" to listOf(
                "Bind" to listOf("begin", "end"),
                "Uniforms" to listOf("uniform", "hasUniform"),
                "Bindings" to listOf("buffer", "image", "textureBindings"),
                "Blocks" to listOf("block", "createBlock", "blockLayout"),
                LIFECYCLE,
            ),
            "org.openrndr.draw.ShadeStyle" to listOf(
                "Transforms" to listOf("fragmentTransform", "vertexTransform", "geometryTransform"),
                "Preambles" to listOf("fragmentPreamble", "vertexPreamble", "geometryPreamble"),
                STYLE_PARAMETERS,
                STYLE_BUFFERS,
                STYLE_IMAGES,
                "Outputs" to listOf("output", "outputs", "suppressDefaultOutput"),
                "Combine" to listOf("plus"),
            ),
            "org.openrndr.draw.ComputeStyle" to listOf(
                "Compute" to listOf("computeTransform", "computePreamble", "workGroupSize"),
                STYLE_PARAMETERS,
                STYLE_BUFFERS,
                STYLE_IMAGES,
                "Execute" to listOf("execute"),
            ),
            "org.openrndr.draw.VertexFormat" to listOf(
                "Attributes" to listOf("position", "normal", "color", "textureCoordinate", "attribute"),
                "Padding" to listOf("padding", "paddingFloat"),
                "Properties" to listOf("items", "size", "alignment", "isInStd430Layout", "hasAttribute"),
            ),
            "org.openrndr.draw.Filter" to listOf(
                "Apply" to listOf("apply"),
                "Properties" to listOf("parameters", "padding", "depthBufferOut"),
                "Lifecycle" to listOf("destroy", "close", "use", "untrack"),
            ),
            LAYER to LAYER_SECTIONS,
            COMPOSITE to LAYER_SECTIONS + LIFECYCLE,
            "org.openrndr.draw.VolumeTexture" to listOf(
                "Properties" to listOf("width", "height", "depth", "format", "type", "levels"),
                "Sampling" to TEXTURE_SAMPLING,
                "Write" to listOf("fill", "write"),
                "Read" to listOf("read"),
                "Copy" to listOf("copyTo"),
                LIFECYCLE,
            ),
        )

        const val TRANSFORM_BUILDER = "org.openrndr.math.transforms.TransformBuilder"
        const val TRANSFORM = "Transform"
        val TRANSFORM_BUILDER_ORDER = listOf("translate", "rotate", "scale", "multiply", "transform")
        val TRANSFORM_BUILDER_MEMBER = Key.create<Boolean>("openrndr.transformBuilderMember")
        const val RENDER_TARGET_BUILDER = "org.openrndr.draw.RenderTargetBuilder"
        const val ATTACH_NEW = "Attach New"
        const val ATTACH_EXISTING = "Attach Existing"
        val ATTACHMENT_ORDER = listOf("colorBuffer", "depthBuffer", "arrayTexture", "cubemap", "arrayCubemap", "volumeTexture")
        /** Parameter types of the attachments of existing buffers and textures. */
        val ATTACHABLES = setOf("ColorBuffer", "DepthBuffer", "ArrayTexture", "ArrayCubemap", "Cubemap", "VolumeTexture")
        val BUILDER_SECTION = Key.create<Int>("openrndr.renderTargetBuilderSection")

        val PROGRAM_ID = ClassId.topLevel(FqName("org.openrndr.Program"))
        val PROGRAM_CLASSES = setOf("org.openrndr.Program", "org.openrndr.ProgramImplementation")

        /** Program's sections after Events, with their members in order; matched by name, so extensions count too. */
        val PROGRAM_SECTIONS = listOf(
            "Drawing" to listOf("drawer", "backgroundColor", "width", "height"),
            "Images" to listOf("loadImage", "loadImageSuspend"),
            "Text" to listOf("loadFont", "writer", "loadFontImageMap"),
            "Time" to listOf("seconds", "clock", "frameCount", "deltaTime"),
            "Input" to listOf("mouse", "keyboard", "pointers", "window"),
            "Coroutines" to listOf("launch", "runBlocking", "async", "dispatcher"),
        )

        /** Events objects whose completion is split into properties and events: `mouse.`, `keyboard.`. */
        val EVENT_CLASSES = setOf("org.openrndr.MouseEvents", "org.openrndr.KeyEvents", "org.openrndr.Keyboard")
        const val EVENTS = "Events"
        val EVENT_ORDER = listOf(
            "buttonUp", "buttonDown", "dragged", "entered", "exited", "moved", "scrolled",
            "keyDown", "keyUp", "keyRepeat", "character",
        )
        val EVENT_CLASS_ID = ClassId.topLevel(FqName("org.openrndr.events.Event"))
        const val DRAWING_PROPERTIES = "Drawing properties"
        const val TRANSFORMS = "Transforms"

        /** The drawer's transform functions besides its matrices (`view`, `model`) and push/pop stacks. */
        val TRANSFORM_FUNCTIONS = setOf("ortho", "perspective", "translate", "rotate", "scale", "lookAt", "isolated", "isolatedWithTarget")
        val TRANSFORM_STACKS = Regex("^(push|pop)(Transforms|Model|View|Projection)$")

        /** A drawer section of drawing functions: names matching [pattern], those in [order] first. */
        class DrawingSection(val title: String, val pattern: Regex, val order: List<String>)

        /** The drawing functions, by kind; variants (circles, lineSegments3d, circleBatch, …) match the same prefixes. */
        val DRAWING = listOf(
            DrawingSection("Text", Regex("^(text|writer)[A-Za-z0-9]*$"), listOf("text", "texts", "textOnContour", "writer")),
            DrawingSection(
                "Shapes",
                Regex("^(circle|rectangle|lineSegment|lineStrip|lineLoop|contour|shape|segment|path|point|composition)[A-Za-z0-9]*$"),
                listOf("circle", "rectangle", "lineSegment", "contour", "shape"),
            ),
            DrawingSection("Images", Regex("^image[A-Za-z0-9]*$"), listOf("image", "images")),
            DrawingSection("Advanced", Regex("^vertexBuffer[A-Za-z0-9]*$"), listOf("vertexBuffer", "vertexBufferInstances")),
        )

        /** The most used members of each drawer section first, in this order; the rest follows. */
        val STYLE_ORDER = listOf("fill", "stroke", "strokeWeight", "lineCap", "lineJoin", "drawStyle", "width", "height", "bounds")

        /** The drawer's size; read-only, so not among the settable style properties, but drawing properties too. */
        val DRAWER_SIZE = setOf("width", "height", "bounds")
        val TRANSFORM_ORDER = listOf("view", "projection", "model", "ortho", "perspective", "translate", "rotate", "scale")

        /** Where [name] goes within its section: its place in [order], or after all of them. */
        fun place(order: List<String>, name: String): Int = order.indexOf(name).let { if (it < 0) order.size else it }

        const val PROPERTIES = "Properties"
        const val LINEAR_OPERATIONS = "Linear Operations"
        const val OPERATIONS = "Operations"
        const val QUERY = "Query"
        const val CONVERT = "Convert"

        /** Named colors are upper case: RED, ORANGE_RED, PURE_D65. */
        val CONSTANT = Regex("^[A-Z][A-Z0-9_]*$")

        fun colorProvider(): OpenrndrColorProvider? =
            ElementColorProvider.EP_NAME.findExtension(OpenrndrColorProvider::class.java)
    }
}

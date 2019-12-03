/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.wire.schema.internal.parser

import com.google.common.collect.ImmutableList
import com.google.common.collect.Range
import com.squareup.wire.schema.Field
import com.squareup.wire.schema.Location
import com.squareup.wire.schema.ProtoFile
import com.squareup.wire.schema.internal.Util

/** Basic parser for `.proto` schema declarations. */
class ProtoParser internal constructor(
  private val location: Location,
  data: CharArray
) {
  private val reader: SyntaxReader = SyntaxReader(data, location)
  private val publicImports = ImmutableList.builder<String>()
  private val imports = ImmutableList.builder<String>()
  private val nestedTypes = ImmutableList.builder<TypeElement>()
  private val services = ImmutableList.builder<ServiceElement>()
  private val extendsList = ImmutableList.builder<ExtendElement>()
  private val options = ImmutableList.builder<OptionElement>()

  /** The number of declarations defined in the current file. */
  private var declarationCount = 0

  /** The syntax of the file, or null if none is defined. */
  private var syntax: ProtoFile.Syntax? = null

  /** Output package name, or null if none yet encountered. */
  private var packageName: String? = null

  /** The current package name + nested type names, separated by dots. */
  private var prefix = ""

  fun readProtoFile(): ProtoFileElement {
    while (true) {
      val documentation = reader.readDocumentation()
      if (reader.exhausted()) {
        return ProtoFileElement(
            location = location,
            packageName = packageName,
            syntax = syntax,
            imports = imports.build(),
            publicImports = publicImports.build(),
            types = nestedTypes.build(),
            services = services.build(),
            extendDeclarations = extendsList.build(),
            options = options.build()
        )
      }

      when (val declaration = readDeclaration(documentation, Context.FILE)) {
        is TypeElement -> nestedTypes.add(declaration)
        is ServiceElement -> services.add(declaration)
        is OptionElement -> options.add(declaration)
        is ExtendElement -> extendsList.add(declaration)
      }
    }
  }

  private fun readDeclaration(documentation: String, context: Context): Any? {
    val index = declarationCount++

    // Skip unnecessary semicolons, occasionally used after a nested message declaration.
    if (reader.peekChar(';')) return null

    val location = reader.location()
    val label = reader.readWord()

    return when {
      label == "package" -> {
        reader.expect(context.permitsPackage(), location) { "'package' in $context" }
        reader.expect(packageName == null, location) { "too many package names" }
        packageName = reader.readName()
        prefix = "$packageName."
        reader.require(';')
        null
      }

      label == "import" -> {
        reader.expect(context.permitsImport(), location) { "'import' in $context" }
        when (val importString = reader.readString()) {
          "public" -> publicImports.add(reader.readString())
          else -> imports.add(importString)
        }
        reader.require(';')
        null
      }

      label == "syntax" -> {
        reader.expect(context.permitsSyntax(), location) { "'syntax' in $context" }
        reader.require('=')
        reader.expect(index == 0, location) {
          "'syntax' element must be the first declaration in a file"
        }
        val syntaxString = reader.readQuotedString()
        try {
          syntax = ProtoFile.Syntax.get(syntaxString)
        } catch (e: IllegalArgumentException) {
          throw reader.unexpected(e.message!!, location)
        }
        reader.require(';')
        null
      }

      label == "option" -> {
        OptionReader(reader).readOption('=').also {
          reader.require(';')
        }
      }

      label == "reserved" -> readReserved(location, documentation)
      label == "message" -> readMessage(location, documentation)
      label == "enum" -> readEnumElement(location, documentation)
      label == "service" -> readService(location, documentation)
      label == "extend" -> readExtend(location, documentation)

      label == "rpc" -> {
        reader.expect(context.permitsRpc(), location) { "'rpc' in $context" }
        readRpc(location, documentation)
      }

      label == "oneof" -> {
        reader.expect(context.permitsOneOf(), location) { "'oneof' must be nested in message" }
        readOneOf(documentation)
      }

      label == "extensions" -> {
        reader.expect(context.permitsExtensions(), location) { "'extensions' must be nested" }
        readExtensions(location, documentation)
      }

      context == Context.MESSAGE || context == Context.EXTEND -> {
        readField(documentation, location, label)
      }

      context == Context.ENUM -> {
        readEnumConstant(documentation, location, label)
      }

      else -> throw reader.unexpected("unexpected label: $label", location)
    }
  }

  /** Reads a message declaration. */
  private fun readMessage(
    location: Location,
    documentation: String
  ): MessageElement {
    val name = reader.readName()
    val fields = ImmutableList.builder<FieldElement>()
    val oneOfs = ImmutableList.builder<OneOfElement>()
    val nestedTypes = ImmutableList.builder<TypeElement>()
    val extensions = ImmutableList.builder<ExtensionsElement>()
    val options = ImmutableList.builder<OptionElement>()
    val reserveds = ImmutableList.builder<ReservedElement>()
    val groups = ImmutableList.builder<GroupElement>()

    val previousPrefix = prefix
    prefix = "$prefix$name."

    reader.require('{')
    while (true) {
      val nestedDocumentation = reader.readDocumentation()
      if (reader.peekChar('}')) break

      when (val declared = readDeclaration(nestedDocumentation, Context.MESSAGE)) {
        is FieldElement -> fields.add(declared)
        is OneOfElement -> oneOfs.add(declared)
        is GroupElement -> groups.add(declared)
        is TypeElement -> nestedTypes.add(declared)
        is ExtensionsElement -> extensions.add(declared)
        is OptionElement -> options.add(declared)
        // Extend declarations always add in a global scope regardless of nesting.
        is ExtendElement -> extendsList.add(declared)
        is ReservedElement -> reserveds.add(declared)
      }
    }

    prefix = previousPrefix

    return MessageElement(
        location = location,
        name = name,
        documentation = documentation,
        nestedTypes = nestedTypes.build(),
        options = options.build(),
        reserveds = reserveds.build(),
        fields = fields.build(),
        oneOfs = oneOfs.build(),
        extensions = extensions.build(),
        groups = groups.build()
    )
  }

  /** Reads an extend declaration. */
  private fun readExtend(location: Location, documentation: String): ExtendElement {
    val name = reader.readName()
    val fields = ImmutableList.builder<FieldElement>()

    reader.require('{')
    while (true) {
      val nestedDocumentation = reader.readDocumentation()
      if (reader.peekChar('}')) break

      when (val declared = readDeclaration(nestedDocumentation, Context.EXTEND)) {
        is FieldElement -> fields.add(declared)
        // TODO: add else clause to catch unexpected declarations.
      }
    }

    return ExtendElement(
        location = location,
        name = name,
        documentation = documentation,
        fields = fields.build()
    )
  }

  /** Reads a service declaration and returns it. */
  private fun readService(location: Location, documentation: String): ServiceElement {
    val name = reader.readName()
    val rpcs = ImmutableList.builder<RpcElement>()
    val options = ImmutableList.builder<OptionElement>()

    reader.require('{')
    while (true) {
      val rpcDocumentation = reader.readDocumentation()
      if (reader.peekChar('}')) break

      when (val declared = readDeclaration(rpcDocumentation, Context.SERVICE)) {
        is RpcElement -> rpcs.add(declared)
        is OptionElement -> options.add(declared)
        // TODO: add else clause to catch unexpected declarations.
      }
    }

    return ServiceElement(
        location = location,
        name = name,
        documentation = documentation,
        rpcs = rpcs.build(),
        options = options.build()
    )
  }

  /** Reads an enumerated type declaration and returns it. */
  private fun readEnumElement(
    location: Location,
    documentation: String
  ): EnumElement {
    val name = reader.readName()
    val constants = ImmutableList.builder<EnumConstantElement>()
    val options = ImmutableList.builder<OptionElement>()

    reader.require('{')
    while (true) {
      val valueDocumentation = reader.readDocumentation()
      if (reader.peekChar('}')) break

      when (val declared = readDeclaration(valueDocumentation, Context.ENUM)) {
        is EnumConstantElement -> constants.add(declared)
        is OptionElement -> options.add(declared)
        // TODO: add else clause to catch unexpected declarations.
      }
    }

    return EnumElement(location, name, documentation, options.build(), constants.build())
  }

  private fun readField(documentation: String, location: Location, word: String): Any {
    val label: Field.Label?
    val type: String
    when (word) {
      "required" -> {
        reader.expect(syntax != ProtoFile.Syntax.PROTO_3, location) {
          "'required' label forbidden in proto3 field declarations"
        }
        label = Field.Label.REQUIRED
        type = reader.readDataType()
      }

      "optional" -> {
        reader.expect(syntax != ProtoFile.Syntax.PROTO_3, location) {
          "'optional' label forbidden in proto3 field declarations"
        }
        label = Field.Label.OPTIONAL
        type = reader.readDataType()
      }

      "repeated" -> {
        label = Field.Label.REPEATED
        type = reader.readDataType()
      }

      else -> {
        reader.expect(syntax == ProtoFile.Syntax.PROTO_3 ||
            (word == "map" && reader.peekChar() == '<'), location) {
          "unexpected label: $word"
        }
        label = null
        type = reader.readDataType(word)
      }
    }

    reader.expect(!type.startsWith("map<") || label == null, location) {
      "'map' type cannot have label"
    }

    return when (type) {
      "group" -> readGroup(location, documentation, label)
      else -> readField(location, documentation, label, type)
    }
  }

  /** Reads an field declaration and returns it. */
  private fun readField(
    location: Location,
    documentation: String,
    label: Field.Label?,
    type: String
  ): FieldElement {
    var documentation = documentation

    val name = reader.readName()
    reader.require('=')
    val tag = reader.readInt()

    var options = OptionReader(reader).readOptions()
    options = options.toMutableList() // Mutable copy for extractDefault.
    val defaultValue = stripDefault(options)
    reader.require(';')

    documentation = reader.tryAppendTrailingDocumentation(documentation)

    return FieldElement(
        location = location,
        label = label,
        type = type,
        name = name,
        defaultValue = defaultValue,
        tag = tag,
        documentation = documentation,
        options = options.toList()
    )
  }

  /**
   * Defaults aren't options. This finds an option named "default", removes, and returns it. Returns
   * null if no default option is present.
   */
  private fun stripDefault(options: MutableList<OptionElement>): String? {
    var result: String? = null
    val i = options.iterator()
    while (i.hasNext()) {
      val element = i.next()
      if (element.name == "default") {
        i.remove()
        result = element.value.toString() // Defaults aren't options!
      }
    }
    return result
  }

  private fun readOneOf(documentation: String): OneOfElement {
    val name = reader.readName()
    val fields = ImmutableList.builder<FieldElement>()
    val groups = ImmutableList.builder<GroupElement>()

    reader.require('{')
    while (true) {
      val nestedDocumentation = reader.readDocumentation()
      if (reader.peekChar('}')) break

      val location = reader.location()
      when (val type = reader.readDataType()) {
        "group" -> groups.add(readGroup(location, nestedDocumentation, null))
        else -> fields.add(readField(location, nestedDocumentation, null, type))
      }
    }

    return OneOfElement(
        name = name,
        documentation = documentation,
        fields = fields.build(),
        groups = groups.build()
    )
  }

  private fun readGroup(
    location: Location,
    documentation: String,
    label: Field.Label?
  ): GroupElement {
    val name = reader.readWord()
    reader.require('=')
    val tag = reader.readInt()
    val fields = ImmutableList.builder<FieldElement>()

    reader.require('{')
    while (true) {
      val nestedDocumentation = reader.readDocumentation()
      if (reader.peekChar('}')) break

      val fieldLocation = reader.location()
      val fieldLabel = reader.readWord()
      when (val field = readField(nestedDocumentation, fieldLocation, fieldLabel)) {
        is FieldElement -> fields.add(field)
        else -> throw reader.unexpected("expected field declaration, was $field")
      }
    }

    return GroupElement(
        label = label,
        location = location,
        name = name,
        tag = tag,
        documentation = documentation,
        fields = fields.build()
    )
  }

  /** Reads a reserved tags and names list like "reserved 10, 12 to 14, 'foo';". */
  private fun readReserved(location: Location, documentation: String): ReservedElement {
    val valuesBuilder = ImmutableList.builder<Any>()

    loop@ while (true) {
      when (reader.peekChar()) {
        '"', '\'' -> {
          valuesBuilder.add(reader.readQuotedString())
        }

        else -> {
          val tagStart = reader.readInt()
          when (reader.peekChar()) {
            ',', ';' -> valuesBuilder.add(tagStart)

            else -> {
              reader.expect(reader.readWord() == "to", location) { "expected ',', ';', or 'to'" }
              val tagEnd = reader.readInt()
              valuesBuilder.add(Range.closed(tagStart, tagEnd))
            }
          }
        }
      }

      when (reader.readChar()) {
        ';' -> break@loop
        ',' -> continue@loop
        else -> throw reader.unexpected("expected ',' or ';'")
      }
    }

    val values = valuesBuilder.build()
    reader.expect(values.isNotEmpty(), location) {
      "'reserved' must have at least one field name or tag"
    }

    return ReservedElement(
        location = location,
        documentation = documentation,
        values = values
    )
  }

  /** Reads extensions like "extensions 101;" or "extensions 101 to max;". */
  private fun readExtensions(
    location: Location,
    documentation: String
  ): ExtensionsElement {
    val start = reader.readInt() // Range start.
    var end = start

    if (reader.peekChar() != ';') {
      reader.expect(reader.readWord() == "to", location) { "expected ';' or 'to'" }
      end = when (val s = reader.readWord()) {
        "max" -> Util.MAX_TAG_VALUE
        else -> s.toInt()
      }
    }
    reader.require(';')

    return ExtensionsElement(
        location = location,
        documentation = documentation,
        start = start,
        end = end
    )
  }

  /** Reads an enum constant like "ROCK = 0;". The label is the constant name. */
  private fun readEnumConstant(
    documentation: String, location: Location, label: String
  ): EnumConstantElement {
    var documentation = documentation

    reader.require('=')
    val tag = reader.readInt()

    val options = OptionReader(reader).readOptions()
    reader.require(';')

    documentation = reader.tryAppendTrailingDocumentation(documentation)

    return EnumConstantElement(
        location = location,
        name = label,
        tag = tag,
        documentation = documentation,
        options = options
    )
  }

  /** Reads an rpc and returns it. */
  private fun readRpc(location: Location, documentation: String): RpcElement {
    val name = reader.readName()

    reader.require('(')
    var requestStreaming = false
    val requestType = when (val word = reader.readWord()) {
      "stream" -> reader.readDataType().also { requestStreaming = true }
      else -> reader.readDataType(word)
    }
    reader.require(')')

    reader.expect(reader.readWord() == "returns", location) { "expected 'returns'" }

    reader.require('(')
    var responseStreaming = false
    val responseType = when (val word = reader.readWord()) {
      "stream" -> reader.readDataType().also { responseStreaming = true }
      else -> reader.readDataType(word)
    }
    reader.require(')')

    val options = ImmutableList.builder<OptionElement>()
    if (reader.peekChar('{')) {
      while (true) {
        val rpcDocumentation = reader.readDocumentation()
        if (reader.peekChar('}')) break

        when (val declared = readDeclaration(rpcDocumentation, Context.RPC)) {
          is OptionElement -> options.add(declared)
          // TODO: add else clause to catch unexpected declarations.
        }
      }
    } else {
      reader.require(';')
    }

    return RpcElement(
        location = location,
        name = name,
        documentation = documentation,
        requestType = requestType,
        responseType = responseType,
        requestStreaming = requestStreaming,
        responseStreaming = responseStreaming,
        options = options.build()
    )
  }

  internal enum class Context {
    FILE,
    MESSAGE,
    ENUM,
    RPC,
    EXTEND,
    SERVICE;

    fun permitsPackage() = this == FILE

    fun permitsSyntax() = this == FILE

    fun permitsImport() = this == FILE

    fun permitsExtensions() = this != FILE

    fun permitsRpc() = this == SERVICE

    fun permitsOneOf() = this == MESSAGE
  }

  companion object {
    /** Parse a named `.proto` schema. */
    fun parse(location: Location, data: String) =
        ProtoParser(location, data.toCharArray()).readProtoFile()
  }
}
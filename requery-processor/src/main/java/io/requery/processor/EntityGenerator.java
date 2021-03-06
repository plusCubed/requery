/*
 * Copyright 2016 requery.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.requery.processor;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.requery.CascadeAction;
import io.requery.Entity;
import io.requery.Persistable;
import io.requery.PropertyNameStyle;
import io.requery.ReferentialAction;
import io.requery.meta.Attribute;
import io.requery.meta.Cardinality;
import io.requery.meta.QueryAttribute;
import io.requery.meta.Type;
import io.requery.meta.TypeBuilder;
import io.requery.proxy.BooleanProperty;
import io.requery.proxy.ByteProperty;
import io.requery.proxy.DoubleProperty;
import io.requery.proxy.EntityProxy;
import io.requery.proxy.FloatProperty;
import io.requery.proxy.IntProperty;
import io.requery.proxy.LongProperty;
import io.requery.proxy.PreInsertListener;
import io.requery.proxy.Property;
import io.requery.proxy.PropertyState;
import io.requery.proxy.ShortProperty;
import io.requery.util.function.Function;
import io.requery.util.function.Supplier;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Generates a java class file from an abstract class marked with the {@link Entity} annotation.
 * This class contains metadata about the table/column mapping that was annotated in the super
 * class. Also contains generated getter/setters for fields in the super class that will be
 * persisted.
 *
 * @author Nikhil Purushe
 */
class EntityGenerator implements SourceGenerator {

    private static final String PROXY_NAME = "$proxy";
    static final String TYPE_NAME = "$TYPE";

    private final ProcessingEnvironment processingEnvironment;
    private final Elements elements;
    private final Types types;
    private final EntityDescriptor entity;
    private final HashSet<String> fieldNames;
    private final TypeElement typeElement;
    private final ClassName typeName;
    private final EntityGraph graph;
    private final EntityNameResolver nameResolver;
    private final Set<TypeGenerationExtension> typeExtensions;
    private final Set<PropertyGenerationExtension> memberExtensions;

    EntityGenerator(ProcessingEnvironment processingEnvironment,
                    EntityGraph graph,
                    EntityDescriptor entity) {
        this.entity = entity;
        this.processingEnvironment = processingEnvironment;
        this.elements = processingEnvironment.getElementUtils();
        this.types = processingEnvironment.getTypeUtils();
        this.graph = graph;
        nameResolver = new EntityNameResolver(graph);
        fieldNames = new HashSet<>();
        typeElement = entity.element();
        typeName = (ClassName) nameResolver.typeNameOf(entity);
        typeExtensions = new HashSet<>();
        memberExtensions = new HashSet<>();

        // android extensions
        typeExtensions.add(new AndroidParcelableExtension(types));
        AndroidObservableExtension observable =
            new AndroidObservableExtension(entity, processingEnvironment);
        typeExtensions.add(observable);
        memberExtensions.add(observable);
    }

    @Override
    public void generate() throws IOException {
        TypeSpec.Builder builder = TypeSpec.classBuilder(typeName)
            .addModifiers(Modifier.PUBLIC)
            .addOriginatingElement(typeElement);
        boolean metadataOnly = entity.isImmutable() || entity.isFinal();
        if (typeElement.getKind().isInterface()) {
            builder.addSuperinterface(ClassName.get(typeElement));
            builder.addSuperinterface(ClassName.get(Persistable.class));

        } else if (!metadataOnly) {
            builder.superclass(ClassName.get(typeElement));
            builder.addSuperinterface(ClassName.get(Persistable.class));
        }
        CodeGeneration.addGeneratedAnnotation(processingEnvironment, builder);
        generateStaticMetadata(builder, metadataOnly);
        if (!metadataOnly) {
            generateConstructors(builder);
            generateMembers(builder);
            generateProxyMethods(builder);
            generateEquals(builder);
            generateHashCode(builder);
            generateToString(builder);
        } else {
            // private constructor
            builder.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE).build());
            generateMembers(builder); // members for builder if needed
            generateImmutableTypeBuildMethod(builder);
        }
        for (TypeGenerationExtension typePart : typeExtensions) {
            typePart.generate(entity, builder);
        }
        CodeGeneration.writeType(processingEnvironment, typeName.packageName(), builder.build());
    }

    private void generateMembers(TypeSpec.Builder typeBuilder) {
        // generate property states
        if (!entity.isStateless()) {
            for (Map.Entry<Element, ? extends AttributeDescriptor> entry :
                entity.attributes().entrySet()) {

                AttributeDescriptor attribute = entry.getValue();
                TypeName stateType = ClassName.get(PropertyState.class);
                FieldSpec field = FieldSpec
                    .builder(stateType, propertyStateFieldName(attribute), Modifier.PRIVATE)
                    .build();
                typeBuilder.addField(field);
            }
        }

        // only generate for interfaces or if the entity is immutable but has no builder
        boolean generateMembers = typeElement.getKind().isInterface() ||
            (entity.isImmutable() && !entity.builderType().isPresent());
        if (generateMembers) {
            for (Map.Entry<Element, ? extends AttributeDescriptor> entry :
                entity.attributes().entrySet()) {
                Element element = entry.getKey();
                AttributeDescriptor attribute = entry.getValue();
                if (element.getKind() == ElementKind.METHOD) {
                    ExecutableElement methodElement = (ExecutableElement) element;
                    TypeMirror typeMirror = methodElement.getReturnType();
                    TypeName fieldTypeName;
                    if (attribute.isIterable()) {
                        fieldTypeName = parameterizedCollectionName(typeMirror);
                    } else if (attribute.isOptional()) {
                        typeMirror = tryFirstTypeArgument(attribute.typeMirror());
                        fieldTypeName = TypeName.get(typeMirror);
                    } else {
                        fieldTypeName = nameResolver.tryGeneratedTypeName(typeMirror);
                    }
                    FieldSpec field = FieldSpec
                        .builder(fieldTypeName, attribute.fieldName(), Modifier.PRIVATE)
                        .build();
                    typeBuilder.addField(field);
                }
            }
        }
    }

    private void generateConstructors(TypeSpec.Builder typeBuilder) {
        // copy the existing constructors
        for (ExecutableElement constructor : ElementFilter.constructorsIn(
                typeElement.getEnclosedElements())) {
            // constructor params
            List<? extends VariableElement> parameters = constructor.getParameters();

            if (!parameters.isEmpty()) {

                MethodSpec.Builder constructorBuilder = MethodSpec.constructorBuilder();
                constructorBuilder.addModifiers(constructor.getModifiers());

                List<String> parameterNames = new ArrayList<>();
                for (VariableElement parameter : parameters) {
                    Modifier[] modifiers = parameter.getModifiers().toArray(
                            new Modifier[parameter.getModifiers().size()]);
                    String parameterName = parameter.getSimpleName().toString();
                    parameterNames.add(parameterName);
                    ParameterSpec.Builder parameterBuilder = ParameterSpec.builder(
                            TypeName.get(parameter.asType()), parameterName, modifiers);
                    constructorBuilder.addParameter(parameterBuilder.build());
                }
                // super parameter/arguments
                StringBuilder superParameters = new StringBuilder();
                int index = 0;
                for (String parameterName : parameterNames) {
                    if (index > 0) {
                        superParameters.append(",");
                    }
                    superParameters.append(parameterName);
                    index++;
                }
                constructorBuilder.addStatement("super(" + superParameters.toString() + ")");
                typeBuilder.addMethod(constructorBuilder.build());
            }
        }
    }

    private void generateProxyMethods(TypeSpec.Builder typeBuilder) {
        // add proxy field
        TypeName proxyName = parameterizedTypeName(EntityProxy.class, typeName);
        FieldSpec.Builder proxyField = FieldSpec.builder(proxyName, PROXY_NAME,
                Modifier.PRIVATE, Modifier.FINAL, Modifier.TRANSIENT);
        proxyField.initializer("new $T(this, $L)", proxyName, TYPE_NAME);
        typeBuilder.addField(proxyField.build());

        for (Map.Entry<Element, ? extends AttributeDescriptor> entry :
            entity.attributes().entrySet()) {

            AttributeDescriptor attribute = entry.getValue();
            boolean isTransient = attribute.isTransient();

            TypeMirror typeMirror = attribute.typeMirror();
            TypeName unboxedTypeName;
            if (attribute.isIterable()) {
                unboxedTypeName = parameterizedCollectionName(typeMirror);
            } else if (attribute.isOptional()) {
                unboxedTypeName = TypeName.get(tryFirstTypeArgument(attribute.typeMirror()));
            } else {
                unboxedTypeName = nameResolver.tryGeneratedTypeName(typeMirror);
            }

            String attributeName = attribute.fieldName();
            String getterName = entry.getValue().getterName();
            String fieldName = Names.upperCaseUnderscore(Names.removeMemberPrefixes(attributeName));
            // getter
            MethodSpec.Builder getter = MethodSpec.methodBuilder(getterName)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(attribute.isOptional() ?
                        TypeName.get(attribute.typeMirror()) : unboxedTypeName);
            if (Mirrors.overridesMethod(types, typeElement, getterName)) {
                getter.addAnnotation(Override.class);
            }
            for (PropertyGenerationExtension snippet : memberExtensions) {
                snippet.addToGetter(attribute, getter);
            }
            if (isTransient) {
                getter.addStatement("return this.$L", attributeName);
            } else if (attribute.isOptional()) {
                getter.addStatement("return $T.ofNullable($L.get($L))",
                    Optional.class, PROXY_NAME, fieldName);
            } else {
                getter.addStatement("return $L.get($L)", PROXY_NAME, fieldName);
            }
            typeBuilder.addMethod(getter.build());

            // setter
            String setterName = entry.getValue().setterName();
            // if read only don't generate a public setter
            boolean readOnly = entity.isReadOnly() || attribute.isReadOnly();
            if (!readOnly) {
                String argumentName =
                    Names.lowerCaseFirst(Names.removeMemberPrefixes(attributeName));
                MethodSpec.Builder setter = MethodSpec.methodBuilder(setterName)
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(unboxedTypeName, argumentName);
                if (isTransient) {
                    setter.addStatement("this.$L = $L", attributeName, argumentName);
                } else {
                    setter.addStatement("$L.set($L, $L)", PROXY_NAME, fieldName, argumentName);
                }
                for (PropertyGenerationExtension snippet : memberExtensions) {
                    snippet.addToSetter(attribute, setter);
                }

                PropertyNameStyle style = entity.propertyNameStyle();
                if (style == PropertyNameStyle.FLUENT || style == PropertyNameStyle.FLUENT_BEAN) {
                    setter.addStatement("return this");
                    setter.returns(typeName);
                }
                typeBuilder.addMethod(setter.build());
            }
        }

        MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC);
        generateListeners(constructor);
        typeBuilder.addMethod(constructor.build());
    }

    private void generateEquals(TypeSpec.Builder typeBuilder) {
        boolean overridesEquals = Mirrors.overridesMethod(types, typeElement, "equals");
        if (!overridesEquals) {
            MethodSpec.Builder equals = CodeGeneration.overridePublicMethod("equals")
                .addParameter(TypeName.OBJECT, "obj")
                .returns(TypeName.BOOLEAN)
                .addStatement("return obj instanceof $T && (($T)obj).$L.equals(this.$L)",
                        typeName, typeName, PROXY_NAME, PROXY_NAME);
            typeBuilder.addMethod(equals.build());
        }
    }

    private void generateHashCode(TypeSpec.Builder typeBuilder) {
        if (!Mirrors.overridesMethod(types, typeElement, "hashCode")) {
            MethodSpec.Builder hashCode = CodeGeneration.overridePublicMethod("hashCode")
                    .returns(TypeName.INT)
                    .addStatement("return $L.hashCode()", PROXY_NAME);
            typeBuilder.addMethod(hashCode.build());
        }
    }

    private void generateToString(TypeSpec.Builder typeBuilder) {
        if (!Mirrors.overridesMethod(types, typeElement, "toString")) {
            MethodSpec.Builder equals = CodeGeneration.overridePublicMethod("toString")
                    .returns(String.class)
                    .addStatement("return $L.toString()", PROXY_NAME);
            typeBuilder.addMethod(equals.build());
        }
    }

    private void generateListeners(MethodSpec.Builder constructor) {
        for (Map.Entry<Element, ? extends ListenerDescriptor> entry :
            entity.listeners().entrySet()) {

            for (Annotation annotation : entry.getValue().listenerAnnotations()) {
                String annotationName = annotation.annotationType().getSimpleName();
                annotationName =
                    annotationName.replace("Persist", "Insert").replace("Remove", "Delete");
                String methodName = Names.lowerCaseFirst(annotationName);
                // avoid hardcoding the package name here
                Element listener = elements.getTypeElement(
                        PreInsertListener.class.getCanonicalName());
                PackageElement packageElement = elements.getPackageOf(listener);

                // generate the listener name
                String packageName = packageElement.getQualifiedName().toString();
                ClassName listenerName = ClassName.get(packageName, annotationName + "Listener");
                ParameterizedTypeName getterType =
                    ParameterizedTypeName.get(listenerName, typeName);

                TypeSpec.Builder listenerBuilder = TypeSpec.anonymousClassBuilder("")
                    .addSuperinterface(getterType)
                    .addMethod(CodeGeneration.overridePublicMethod(methodName)
                            .addParameter(typeName, "entity")
                            .addStatement("$L()", entry.getKey().getSimpleName())
                            .build());

                constructor.addStatement("$L.modifyListeners().add$L($L)", PROXY_NAME,
                        annotationName + "Listener", listenerBuilder.build());
            }
        }
    }

    private void generateType(TypeSpec.Builder typeSpecBuilder, TypeName targetName) {
        ClassName schemaName = ClassName.get(TypeBuilder.class);
        ParameterizedTypeName type = parameterizedTypeName(Type.class, targetName);
        FieldSpec.Builder schemaFieldBuilder = FieldSpec.builder(type, TYPE_NAME,
                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);

        CodeBlock.Builder typeBuilder = CodeBlock.builder().add("new $T<$T>($T.class, $S)\n",
                    schemaName, targetName, targetName, entity.tableName());

        typeBuilder.add(".setBaseType($T.class)\n", ClassName.get(typeElement))
            .add(".setCacheable($L)\n", entity.isCacheable())
            .add(".setImmutable($L)\n", entity.isImmutable())
            .add(".setReadOnly($L)\n", entity.isReadOnly())
            .add(".setStateless($L)\n", entity.isStateless());
        String factoryName = entity.classFactoryName();
        if (!Names.isEmpty(factoryName)) {
            typeBuilder.add(".setFactory(new $L())\n", ClassName.bestGuess(factoryName));
        } else if (entity.isImmutable()) {

            // the builder name (if there is no builder than this class is the builder)
            TypeName builderName = entity.builderType().map(
                element -> TypeName.get(element.asType())).orElse(typeName);

            TypeSpec.Builder typeFactory = TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(parameterizedTypeName(Supplier.class, builderName));
            MethodSpec.Builder buildMethod =
                CodeGeneration.overridePublicMethod("get").returns(builderName);

            if (entity.builderFactoryMethod().isPresent()) {
                buildMethod.addStatement("return $T.$L()", targetName,
                    entity.builderFactoryMethod()
                        .map(method -> method.getSimpleName().toString()).orElse(""));
            } else {
                buildMethod.addStatement("return new $T()", builderName);
            }
            typeFactory.addMethod(buildMethod.build());
            typeBuilder.add(".setBuilderFactory($L)\n", typeFactory.build());

            TypeSpec.Builder buildFunction = TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(parameterizedTypeName(Function.class, builderName, targetName))
                .addMethod(
                    CodeGeneration.overridePublicMethod("apply")
                        .addParameter(builderName, "value")
                        .addStatement("return value.build()")
                        .returns(targetName)
                        .build());
            typeBuilder.add(".setBuilderFunction($L)\n", buildFunction.build());
        } else {
            TypeSpec.Builder typeFactory = TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(parameterizedTypeName(Supplier.class, targetName))
                .addMethod(
                    CodeGeneration.overridePublicMethod("get")
                        .addStatement("return new $T()", targetName)
                        .returns(targetName)
                        .build());
            typeBuilder.add(".setFactory($L)\n", typeFactory.build());
        }

        ParameterizedTypeName proxyType = parameterizedTypeName(EntityProxy.class, targetName);
        TypeSpec.Builder proxyProvider = TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(parameterizedTypeName(Function.class, targetName,
                    proxyType));
        MethodSpec.Builder proxyFunction = CodeGeneration.overridePublicMethod("apply")
            .addParameter(targetName, "entity")
            .returns(proxyType);
        if (entity.isImmutable() || entity.isFinal()) {
            proxyFunction.addStatement("return new $T(entity, $L)", proxyType, TYPE_NAME);
        } else {
            proxyFunction.addStatement("return entity.$L", PROXY_NAME);
        }
        proxyProvider.addMethod(proxyFunction.build());

        typeBuilder.add(".setProxyProvider($L)\n", proxyProvider.build());

        if (entity.tableAttributes() != null && entity.tableAttributes().length > 0) {
            StringJoiner joiner = new StringJoiner(",", "new String[] {", "}");
            for (String attribute : entity.tableAttributes()) {
                joiner.add("\"" + attribute + "\"");
            }
            typeBuilder.add(".setTableCreateAttributes($L)\n", joiner.toString());
        }
        fieldNames.forEach(name -> typeBuilder.add(".addAttribute($L)\n", name));
        typeBuilder.add(".build()");
        schemaFieldBuilder.initializer("$L", typeBuilder.build());
        typeSpecBuilder.addField(schemaFieldBuilder.build());
    }

    private void generateImmutableTypeBuildMethod(TypeSpec.Builder builder) {
        if (entity.isImmutable() &&
            !entity.builderType().isPresent() && entity.factoryMethod().isPresent()) {

            ExecutableElement createMethod = entity.factoryMethod().get();
            // now match the builder fields to the parameters...
            Map<Element, AttributeDescriptor> attributes = new LinkedHashMap<>();
            attributes.putAll(entity.attributes());

            List<? extends VariableElement> parameters = createMethod.getParameters();
            List<String> argumentNames = new ArrayList<>();

            for (VariableElement parameter : parameters) {
                Element matched = null;
                // straight forward case type and name are the same
                for (Map.Entry<Element, AttributeDescriptor> entry : attributes.entrySet()) {
                    AttributeDescriptor attribute = entry.getValue();
                    String fieldName = attribute.fieldName();
                    if (fieldName.equalsIgnoreCase(parameter.getSimpleName().toString())) {
                        argumentNames.add(fieldName);
                        matched = entry.getKey();
                    }
                }
                // remove this element since it was found
                if (matched != null) {
                    attributes.remove(matched);
                }
            }
            // TODO need more validation here
            StringJoiner joiner = new StringJoiner(",");
            argumentNames.forEach(name -> joiner.add("$L"));
            Object[] args = new Object[2 + argumentNames.size()];
            args[0] = ClassName.get(entity.element());
            args[1] = createMethod.getSimpleName();
            System.arraycopy(argumentNames.toArray(), 0, args, 2, argumentNames.size());
            builder.addMethod(MethodSpec.methodBuilder("build")
                .returns(ClassName.get(entity.element()))
                .addStatement("return $T.$L(" + joiner.toString() + ")", args).build());
        }
    }

    private void addPropertyMethods(TypeSpec.Builder builder, GeneratedProperty property) {
        String suffix = property.methodSuffix();
        TypeName targetName = property.targetName();
        TypeName propertyTypeName = property.propertyTypeName();
        // get
        MethodSpec.Builder getMethod = CodeGeneration.overridePublicMethod("get" + suffix)
            .addParameter(targetName, "entity")
            .returns(propertyTypeName);
        if (property.isWriteOnly()) {
            getMethod.addStatement("throw new UnsupportedOperationException()");
        } else {
            getMethod.addStatement(property.useMethod() ?
                "return entity.$L()" : "return entity.$L", property.readName());
        }
        // set
        MethodSpec.Builder setMethod = CodeGeneration.overridePublicMethod("set" + suffix)
            .addParameter(targetName, "entity")
            .addParameter(propertyTypeName, "value");
        if (property.isReadOnly()) {
            setMethod.addStatement("throw new UnsupportedOperationException()");
        } else {
            CodeBlock.Builder setterBlock = CodeBlock.builder();
            if (property.isNullable()) {
                setterBlock.beginControlFlow("if(value != null)");
            }
            setterBlock.addStatement(property.useMethod() ?
                "entity.$L(value)" : "entity.$L = value", property.writeName());
            if (property.isNullable()) {
                setterBlock.endControlFlow();
            }
            setMethod.addCode(setterBlock.build());
        }
        builder.addMethod(getMethod.build());
        builder.addMethod(setMethod.build());
    }

    private void generateStaticMetadata(TypeSpec.Builder typeBuilder, boolean metadataOnly) {
        TypeName targetName = metadataOnly? ClassName.get(entity.element()) : typeName;
        for (Map.Entry<Element, ? extends AttributeDescriptor> entry :
            entity.attributes().entrySet()) {

            AttributeDescriptor attribute = entry.getValue();
            if (attribute.isTransient()) {
                continue;
            }
            FieldSpec field = generateAttribute(attribute, targetName);
            typeBuilder.addField(field);
        }
        generateType(typeBuilder, targetName);
    }

    private FieldSpec generateAttribute(AttributeDescriptor attribute, TypeName targetName) {
        TypeMirror typeMirror = attribute.typeMirror();
        TypeName attributeTypeName;
        if (attribute.isIterable()) {
            typeMirror = tryFirstTypeArgument(attribute.typeMirror());
            attributeTypeName = parameterizedCollectionName(attribute.typeMirror());
        } else if (attribute.isOptional()) {
            typeMirror = tryFirstTypeArgument(attribute.typeMirror());
            attributeTypeName = TypeName.get(typeMirror);
        } else {
            attributeTypeName = nameResolver.generatedTypeNameOf(typeMirror).orElse(null);
        }
        if (attributeTypeName == null) {
            attributeTypeName = boxedTypeName(typeMirror);
        }

        // if it's an association don't make it available as a query attribute
        boolean isQueryable = attribute.cardinality() == null || attribute.isForeignKey();
        Class<?> attributeType = isQueryable ? QueryAttribute.class : Attribute.class;
        CodeBlock.Builder builder = CodeBlock.builder();

        if (attribute.isIterable()) {
            typeMirror = tryFirstTypeArgument(typeMirror);
            TypeName name = nameResolver.tryGeneratedTypeName(typeMirror);
            TypeElement collectionElement = (TypeElement) types.asElement(attribute.typeMirror());

            ParameterizedTypeName builderName = parameterizedTypeName(
                attribute.builderClass(), targetName, attributeTypeName, name);

            builder.add("\nnew $T($S, $T.class, $T.class)\n",
                builderName, attribute.name(), ClassName.get(collectionElement), name);

        } else if (attribute.isMap()) {
            List<TypeMirror> parameters = Mirrors.listGenericTypeArguments(typeMirror);
            // key type
            TypeName keyName = TypeName.get(parameters.get(0));
            // value type
            typeMirror = parameters.get(1);
            TypeName valueName = nameResolver.tryGeneratedTypeName(typeMirror);

            TypeElement valueElement = (TypeElement) types.asElement(attribute.typeMirror());
            ParameterizedTypeName builderName = parameterizedTypeName(
                attribute.builderClass(), targetName, attributeTypeName, keyName, valueName);

            builder.add("\nnew $T($S, $T.class, $T.class, $T.class)\n", builderName,
                attribute.name(), ClassName.get(valueElement), keyName, valueName);
        } else {
            ParameterizedTypeName builderName = parameterizedTypeName(
                attribute.builderClass(), targetName, attributeTypeName);
            TypeName classType = attributeTypeName;
            if (typeMirror.getKind().isPrimitive()) {
                // if primitive just use the primitive class not the boxed version
                classType = TypeName.get(typeMirror);
            }
            String statement;
            if (Mirrors.listGenericTypeArguments(typeMirror).size() > 0) {
                // use the erased type and cast to class
                classType = TypeName.get(types.erasure(typeMirror));
                statement = "\nnew $T($S, (Class)$T.class)\n";
            } else {
                statement ="\nnew $T($S, $T.class)\n";
            }
            builder.add(statement, builderName, attribute.name(), classType);
        }

        ParameterizedTypeName type =
            parameterizedTypeName(attributeType, targetName, attributeTypeName);
        String attributeName = Names.upperCaseUnderscore(
            Names.removeMemberPrefixes(attribute.fieldName()));
        fieldNames.add(attributeName);

        FieldSpec.Builder fieldBuilder = FieldSpec.builder(type, attributeName,
            Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);

        generateProperties(attribute, typeMirror, targetName, attributeTypeName, builder);
        // attribute builder properties
        if (attribute.isKey()) {
            builder.add(".setKey(true)\n");
        }
        builder.add(".setGenerated($L)\n", attribute.isGenerated());
        builder.add(".setLazy($L)\n", attribute.isLazy());
        builder.add(".setNullable($L)\n", attribute.isNullable());
        builder.add(".setUnique($L)\n", attribute.isUnique());
        if (!Names.isEmpty(attribute.defaultValue())) {
            builder.add(".setDefaultValue($S)\n", attribute.defaultValue());
        }
        if (!Names.isEmpty(attribute.collate())) {
            builder.add(".setCollate($S)\n", attribute.collate());
        }
        if (attribute.columnLength() != null) {
            builder.add(".setLength($L)\n", attribute.columnLength());
        }
        if (attribute.isVersion()) {
            builder.add(".setVersion($L)\n", attribute.isVersion());
        }
        if (attribute.converterName() != null) {
            builder.add(".setConverter(new $L())\n", attribute.converterName());
        }
        if (attribute.isForeignKey()) {
            builder.add(".setForeignKey($L)\n", attribute.isForeignKey());

            graph.referencingEntity(attribute).ifPresent(referenced -> {

                builder.add(".setReferencedClass($T.class)\n", referenced.isImmutable() ?
                        TypeName.get(referenced.element().asType()) :
                        nameResolver.typeNameOf(referenced));

                graph.referencingAttribute(attribute, referenced).ifPresent(
                    referencedAttribute -> {
                        String name = Names.upperCaseUnderscore(referencedAttribute.fieldName());
                        TypeSpec provider = CodeGeneration.createAnonymousSupplier(
                            ClassName.get(Attribute.class),
                            CodeBlock.builder().addStatement("return $T.$L",
                                nameResolver.typeNameOf(referenced), name).build());

                        builder.add(".setReferencedAttribute($L)\n", provider);
                    });
            });
        }
        if (attribute.isIndexed()) {
            builder.add(".setIndexed($L)\n", attribute.isIndexed());
            if (!attribute.indexNames().isEmpty()) {
                StringJoiner joiner = new StringJoiner(",");
                attribute.indexNames().forEach(name -> joiner.add("$S"));
                builder.add(".setIndexNames(" + joiner + ")\n", attribute.indexNames().toArray());
            }
        }
        if (attribute.deleteAction() != null) {
            builder.add(".setDeleteAction($T.$L)\n",
                ClassName.get(ReferentialAction.class), attribute.deleteAction());
        }
        if (attribute.updateAction() != null) {
            builder.add(".setUpdateAction($T.$L)\n",
                ClassName.get(ReferentialAction.class), attribute.updateAction());
        }
        if (!attribute.cascadeActions().isEmpty()) {
            StringJoiner joiner = new StringJoiner(",");
            attribute.cascadeActions().forEach(action -> joiner.add("$T.$L"));
            int index = 0;
            ClassName cascadeClass = ClassName.get(CascadeAction.class);
            Object[] args = new Object[attribute.cascadeActions().size()*2];
            for (CascadeAction action : attribute.cascadeActions()) {
                args[index++] = cascadeClass;
                args[index++] = action;
            }
            builder.add(".setCascadeAction(" + joiner +  ")\n", args);
        }
        if (attribute.cardinality() != null) {
            builder.add(".setCardinality($T.$L)\n",
                ClassName.get(Cardinality.class), attribute.cardinality());

            Optional<EntityDescriptor> referencingEntity = graph.referencingEntity(attribute);

            if (referencingEntity.isPresent()) {
                EntityDescriptor referenced = referencingEntity.get();
                Set<AttributeDescriptor> mappings =
                    graph.mappedAttributes(entity, attribute, referenced);

                if (attribute.cardinality() == Cardinality.MANY_TO_MANY) {
                    Optional<AssociativeEntityDescriptor> joinEntity = attribute.associativeEntity();
                    if (joinEntity.isPresent()) {
                        // generate a special type for the junction table (with attributes)
                        generateJunctionType(attribute);
                        builder.add(".setReferencedClass($T.class)\n",
                            nameResolver.joinEntityName(joinEntity.get(), entity, referenced));
                    } else if ( mappings.size() == 1) {
                        AttributeDescriptor mapped = mappings.iterator().next();
                        joinEntity = mapped.associativeEntity();
                        if (joinEntity.isPresent()) {
                            builder.add(".setReferencedClass($T.class)\n",
                                nameResolver.joinEntityName(joinEntity.get(), referenced, entity));
                        }
                    }
                }
                if (mappings.size() == 1) {
                    AttributeDescriptor mapped = mappings.iterator().next();
                    String staticMemberName = Names.upperCaseUnderscore(mapped.fieldName());

                    TypeSpec provider = CodeGeneration.createAnonymousSupplier(
                        ClassName.get(Attribute.class),
                        CodeBlock.builder().addStatement("return $T.$L",
                            nameResolver.typeNameOf(referenced), staticMemberName).build());
                    builder.add(".setMappedAttribute($L)\n", provider);
                }
            }
        }
        builder.add(".build()");
        return fieldBuilder.initializer("$L", builder.build()).build();
    }

    private void generateProperties(AttributeDescriptor attribute,
                                    TypeMirror typeMirror,
                                    TypeName targetName,
                                    TypeName attributeName,
                                    CodeBlock.Builder builder) {
        // boxed get/set using Objects
        Class propertyClass = propertyClassFor(typeMirror);
        ParameterizedTypeName propertyType = propertyName(propertyClass, targetName, attributeName);

        TypeSpec.Builder propertyBuilder = TypeSpec.anonymousClassBuilder("")
            .addSuperinterface(propertyType);

        boolean isNullable = typeMirror.getKind().isPrimitive() && attribute.isNullable();
        boolean accessWithMethod = entity.isImmutable() || entity.isFinal();
        String getName = accessWithMethod? attribute.getterName() : attribute.fieldName();
        String setName = entity.isFinal() ? attribute.setterName() : getName;
        GeneratedProperty boxed =
            new GeneratedProperty.Builder(getName, setName, targetName, attributeName)
                .setNullable(isNullable)
                .setReadOnly(entity.isImmutable())
                .setUseMethod(accessWithMethod)
                .build();
        addPropertyMethods(propertyBuilder, boxed);

        // additional primitive get/set if the type is primitive
        if (propertyClass != Property.class) {
            TypeName primitiveType = TypeName.get(attribute.typeMirror());
            String name = Names.upperCaseFirst(attribute.typeMirror().toString());

            addPropertyMethods(propertyBuilder, new GeneratedProperty.Builder(
                getName, setName, targetName, primitiveType)
                .setSuffix(name)
                .setReadOnly(entity.isImmutable())
                .setUseMethod(accessWithMethod)
                .build());
        }
        builder.add(".setProperty($L)\n", propertyBuilder.build());

        // property state get/set
        if (!entity.isStateless()) {
            ClassName stateClass = ClassName.get(PropertyState.class);
            TypeSpec.Builder stateType = TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(parameterizedTypeName(Property.class, targetName, stateClass));
            String fieldName = propertyStateFieldName(attribute);
            addPropertyMethods(stateType,
                new GeneratedProperty.Builder(fieldName, targetName, stateClass).build());
            builder.add(".setPropertyState($L)\n", stateType.build());
        }

        // if immutable add setter for the builder
        if (entity.isImmutable()) {
            String propertyName = attribute.fieldName();
            TypeName builderName = typeName;
            boolean useSetter = false;
            Optional<TypeElement> builderType = entity.builderType();
            if (builderType.isPresent()) {
                propertyName = attribute.setterName();
                builderName = TypeName.get(builderType.get().asType());
                useSetter = true;
                for (ExecutableElement method :
                    ElementFilter.methodsIn(builderType.get().getEnclosedElements())) {
                    List<? extends VariableElement> parameters = method.getParameters();
                    String name = Names.removeMethodPrefixes(method.getSimpleName());
                    // probable setter for this attribute
                    // (some builders have with<Property> setters so strip that
                    if ((name.startsWith("with") && name.length() > 4 &&
                        Character.isUpperCase(name.charAt(4))) ||
                        name.equalsIgnoreCase(attribute.fieldName()) && parameters.size() == 1) {
                        propertyName = method.getSimpleName().toString();
                        break;
                    }
                }
            }
            propertyType = propertyName(propertyClass, builderName, attributeName);
            TypeSpec.Builder builderProperty = TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(propertyType);

            addPropertyMethods(builderProperty,
                new GeneratedProperty.Builder(propertyName, builderName, attributeName)
                    .setWriteOnly(true)
                    .setUseMethod(useSetter)
                    .build());
            if (propertyClass != Property.class) {
                TypeName primitiveType = TypeName.get(attribute.typeMirror());
                String name = Names.upperCaseFirst(attribute.typeMirror().toString());
                addPropertyMethods(builderProperty, new GeneratedProperty.Builder(
                    propertyName, builderName, primitiveType)
                    .setSuffix(name)
                    .setUseMethod(useSetter)
                    .setWriteOnly(true)
                    .build());
            }
            builder.add(".setBuilderProperty($L)\n", builderProperty.build());
        }
    }

    private static ParameterizedTypeName propertyName(Class type,
                                                      TypeName targetName, TypeName fieldName) {
        return type == Property.class ?
            parameterizedTypeName(type, targetName, fieldName) :
            parameterizedTypeName(type, targetName);
    }

    private Class propertyClassFor(TypeMirror typeMirror) {
        Class propertyClass = Property.class;
        if (typeMirror.getKind().isPrimitive()) {
            switch (typeMirror.getKind()) {
                case BOOLEAN:
                    propertyClass = BooleanProperty.class;
                    break;
                case BYTE:
                    propertyClass = ByteProperty.class;
                    break;
                case SHORT:
                    propertyClass = ShortProperty.class;
                    break;
                case INT:
                    propertyClass = IntProperty.class;
                    break;
                case LONG:
                    propertyClass = LongProperty.class;
                    break;
                case FLOAT:
                    propertyClass = FloatProperty.class;
                    break;
                case DOUBLE:
                    propertyClass = DoubleProperty.class;
                    break;
            }
        }
        return propertyClass;
    }

    private String propertyStateFieldName(AttributeDescriptor attribute) {
        return "$" + attribute.fieldName() + "_state";
    }

    private void generateJunctionType(AttributeDescriptor attribute) {
        graph.referencingEntity(attribute).ifPresent(referencing -> {
            JoinEntityGenerator generator = new JoinEntityGenerator(
                processingEnvironment, nameResolver, entity, referencing, attribute);
            try {
                generator.generate();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private TypeName boxedTypeName(TypeMirror typeMirror) {
        if (typeMirror.getKind().isPrimitive()) {
            TypeElement boxed = types.boxedClass((PrimitiveType) typeMirror);
            return TypeName.get(boxed.asType());
        }
        return TypeName.get(typeMirror);
    }

    private ParameterizedTypeName parameterizedCollectionName(TypeMirror typeMirror) {
        TypeMirror genericType = tryFirstTypeArgument(typeMirror);
        TypeName elementName = nameResolver.tryGeneratedTypeName(genericType);
        TypeElement collectionElement = (TypeElement) types.asElement(typeMirror);
        ClassName collectionName = ClassName.get(collectionElement);
        return ParameterizedTypeName.get(collectionName, elementName);
    }

    private static TypeMirror tryFirstTypeArgument(TypeMirror typeMirror) {
        List<TypeMirror> args = Mirrors.listGenericTypeArguments(typeMirror);
        return args.isEmpty() ? typeMirror : args.get(0);
    }

    private static ParameterizedTypeName parameterizedTypeName(Class<?> rawType,
                                                               TypeName... typeArguments) {
        return ParameterizedTypeName.get(ClassName.get(rawType), typeArguments);
    }
}

/*
 * Copyright 2017-2019 original authors
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

package io.micronaut.inject.beans.visitor;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospectionReference;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.TypedElement;
import io.micronaut.inject.beans.AbstractBeanIntrospection;
import io.micronaut.inject.beans.AbstractBeanIntrospectionReference;
import io.micronaut.inject.beans.AbstractBeanProperty;
import io.micronaut.inject.writer.AbstractAnnotationMetadataWriter;
import io.micronaut.inject.writer.ClassWriterOutputVisitor;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A class file writer that writes a {@link io.micronaut.core.beans.BeanIntrospectionReference} and associated
 * {@link io.micronaut.core.beans.BeanIntrospection} for the given class.
 *
 * @author graemerocher
 * @since 1.1
 */
@Internal
public class BeanIntrospectionWriter extends AbstractAnnotationMetadataWriter {
    private static final Method METHOD_ADD_PROPERTY = Method.getMethod(ReflectionUtils.getRequiredInternalMethod(AbstractBeanIntrospection.class, "addProperty", BeanProperty.class));
    private static final String REFERENCE_SUFFIX = "$IntrospectionRef";
    private static final String INTROSPECTION_SUFFIX = "$Introspection";

    private final ClassWriter referenceWriter;
    private final String introspectionName;
    private final Type introspectionType;
    private final Type beanType;
    private final ClassWriter introspectionWriter;
    private final List<BeanPropertyWriter> propertyDefinitions = new ArrayList<>();
    private int propertyIndex = 0;

    /**
     * Default constructor.
     * @param className The class name
     * @param beanAnnotationMetadata The bean annotation metadata
     */
    BeanIntrospectionWriter(String className, AnnotationMetadata beanAnnotationMetadata) {
        super(computeReferenceName(className), beanAnnotationMetadata, true);
        this.referenceWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        this.introspectionWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        this.introspectionName = computeIntrospectionName(className);
        this.introspectionType = getTypeReference(introspectionName);
        this.beanType = getTypeReference(className);
    }

    /**
     * The instropection type.
     * @return The type
     */
    public Type getIntrospectionType() {
        return introspectionType;
    }

    /**
     * The bean type.
     * @return The bean type
     */
    public Type getBeanType() {
        return beanType;
    }

    /**
     * Visit a property.
     *
     * @param type The property type
     * @param name The property name
     * @param isReadOnly Is the property read only
     * @param annotationMetadata The property annotation metadata
     * @param typeArguments The type arguments
     */
    void visitProperty(
            @Nonnull TypedElement type,
            @Nonnull String name,
            boolean isReadOnly,
            @Nullable AnnotationMetadata annotationMetadata,
            @Nullable Map<String, ClassElement> typeArguments) {

        final Type propertyType = getTypeForElement(type);

        propertyDefinitions.add(
                new BeanPropertyWriter(
                        this,
                        propertyType,
                        name,
                        propertyIndex++,
                        annotationMetadata,
                        typeArguments
        ));
    }

    @Override
    public void accept(ClassWriterOutputVisitor classWriterOutputVisitor) throws IOException {
        // write the annotation metadata
        if (annotationMetadataWriter != null) {
            annotationMetadataWriter.accept(classWriterOutputVisitor);
            annotationMetadataWriter.clearDefaults();
        }
        // write the reference
        writeIntrospectionReference(classWriterOutputVisitor);
        // write the introspection
        writeIntrospectionClass(classWriterOutputVisitor);
    }

    private void writeIntrospectionClass(ClassWriterOutputVisitor classWriterOutputVisitor) throws IOException {
        final Type superType = Type.getType(AbstractBeanIntrospection.class);

        try (OutputStream introspectionStream = classWriterOutputVisitor.visitClass(introspectionName)) {

            startFinalClass(introspectionWriter, introspectionType.getInternalName(), superType);
            final GeneratorAdapter constructorWriter = startConstructor(introspectionWriter);

            // writer the constructor
            constructorWriter.loadThis();
            // 1st argument: The bean type
            constructorWriter.push(beanType);

            // 2nd argument: The annotation metadata
            if (annotationMetadata == null || annotationMetadata == AnnotationMetadata.EMPTY_METADATA) {
                constructorWriter.visitInsn(ACONST_NULL);
            } else {
                // retrieved from BeanIntrospectionReference.$ANNOTATION_METADATA
                constructorWriter.getStatic(
                        targetClassType,
                        AbstractAnnotationMetadataWriter.FIELD_ANNOTATION_METADATA, Type.getType(AnnotationMetadata.class));
            }

            // 3rd argument: The number of properties
            constructorWriter.push(propertyDefinitions.size());

            invokeConstructor(
                    constructorWriter,
                    AbstractBeanIntrospection.class,
                    Class.class,
                    AnnotationMetadata.class,
                    int.class);

            // process the properties, creating them etc.
            for (BeanPropertyWriter propertyWriter : propertyDefinitions) {
                propertyWriter.accept(classWriterOutputVisitor);
                final Type writerType = propertyWriter.getType();
                constructorWriter.loadThis();
                constructorWriter.newInstance(writerType);
                constructorWriter.dup();
                constructorWriter.loadThis();
                constructorWriter.invokeConstructor(writerType, new Method(CONSTRUCTOR_NAME, getConstructorDescriptor(BeanIntrospection.class)));
                constructorWriter.visitMethodInsn(INVOKESPECIAL,
                        superType.getInternalName(),
                        METHOD_ADD_PROPERTY.getName(),
                        METHOD_ADD_PROPERTY.getDescriptor(),
                        false);
            }

            // RETURN
            constructorWriter.visitInsn(RETURN);
            // MAXSTACK = 2
            // MAXLOCALS = 1
            constructorWriter.visitMaxs(2, 1);


            // write the instantiate method
            final GeneratorAdapter instantiateMethod = startPublicMethod(introspectionWriter, "instantiate", Object.class.getName());
            pushNewInstance(instantiateMethod, beanType);
            instantiateMethod.visitInsn(ARETURN);
            instantiateMethod.visitMaxs(2, 1);
            instantiateMethod.visitEnd();

            introspectionStream.write(introspectionWriter.toByteArray());
        }
    }

    private void writeIntrospectionReference(ClassWriterOutputVisitor classWriterOutputVisitor) throws IOException {
        Type superType = Type.getType(AbstractBeanIntrospectionReference.class);
        final String referenceName = targetClassType.getClassName();
        classWriterOutputVisitor.visitServiceDescriptor(BeanIntrospectionReference.class, referenceName);

        try (OutputStream referenceStream = classWriterOutputVisitor.visitClass(referenceName)) {
            startFinalClass(referenceWriter, targetClassType.getInternalName(), superType);
            final ClassWriter classWriter = generateClassBytes(referenceWriter);
            referenceStream.write(classWriter.toByteArray());
        }
    }

    private ClassWriter generateClassBytes(ClassWriter classWriter) {
        writeAnnotationMetadataStaticInitializer(classWriter);

        GeneratorAdapter cv = startConstructor(classWriter);

        // ALOAD 0
        cv.loadThis();

        // INVOKESPECIAL AbstractBeanIntrospectionReference.<init>
        invokeConstructor(cv, AbstractBeanIntrospectionReference.class);

        // RETURN
        cv.visitInsn(RETURN);
        // MAXSTACK = 2
        // MAXLOCALS = 1
        cv.visitMaxs(2, 1);

        // start method: BeanIntrospection load()
        GeneratorAdapter loadMethod = startPublicMethodZeroArgs(classWriter, BeanIntrospection.class, "load");

        // return new BeanIntrospection()
        pushNewInstance(loadMethod, this.introspectionType);

        // RETURN
        loadMethod.returnValue();
        loadMethod.visitMaxs(2, 1);

        // start method: Class getBeanType()
        GeneratorAdapter getBeanType = startPublicMethodZeroArgs(classWriter, Class.class, "getBeanType");
        getBeanType.push(beanType);
        getBeanType.returnValue();
        getBeanType.visitMaxs(2, 1);


        writeGetAnnotationMetadataMethod(classWriter);

        return classWriter;
    }

    @NotNull
    private static String computeReferenceName(String className) {
        String packageName = NameUtils.getPackageName(className);
        final String shortName = NameUtils.getSimpleName(className);
        return packageName + ".$" + shortName + REFERENCE_SUFFIX;
    }

    @NotNull
    private static String computeIntrospectionName(String className) {
        String packageName = NameUtils.getPackageName(className);
        final String shortName = NameUtils.getSimpleName(className);
        return packageName + ".$" + shortName + INTROSPECTION_SUFFIX;
    }
}
/*
 * Copyright 2002-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.orm.jpa.persistenceunit;

import java.lang.reflect.Executable;
import java.util.List;

import javax.lang.model.element.Modifier;

import jakarta.persistence.Converter;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.IdClass;

import org.springframework.aot.generate.GeneratedMethod;
import org.springframework.aot.generate.GenerationContext;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.beans.factory.aot.BeanRegistrationAotContribution;
import org.springframework.beans.factory.aot.BeanRegistrationAotProcessor;
import org.springframework.beans.factory.aot.BeanRegistrationCode;
import org.springframework.beans.factory.aot.BeanRegistrationCodeFragments;
import org.springframework.beans.factory.support.RegisteredBean;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.javapoet.CodeBlock;
import org.springframework.javapoet.ParameterizedTypeName;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;

/**
 * {@link BeanRegistrationAotProcessor} implementations for persistence managed
 * types.
 *
 * <p>Allows a {@link PersistenceManagedTypes} to be instantiated at build-time
 * and replaced by a hard-coded list of managed class names and packages.
 *
 * @author Stephane Nicoll
 * @author Sebastien Deleuze
 * @since 6.0
 */
class PersistenceManagedTypesBeanRegistrationAotProcessor implements BeanRegistrationAotProcessor {

	@Nullable
	@Override
	public BeanRegistrationAotContribution processAheadOfTime(RegisteredBean registeredBean) {
		if (PersistenceManagedTypes.class.isAssignableFrom(registeredBean.getBeanClass())) {
			return BeanRegistrationAotContribution.ofBeanRegistrationCodeFragmentsCustomizer(codeFragments ->
					new JpaManagedTypesBeanRegistrationCodeFragments(codeFragments, registeredBean));
		}
		return null;
	}

	private static class JpaManagedTypesBeanRegistrationCodeFragments extends BeanRegistrationCodeFragments {

		private static final ParameterizedTypeName LIST_OF_STRINGS_TYPE = ParameterizedTypeName.get(List.class, String.class);

		private final RegisteredBean registeredBean;

		private final BindingReflectionHintsRegistrar bindingRegistrar = new BindingReflectionHintsRegistrar();

		public JpaManagedTypesBeanRegistrationCodeFragments(BeanRegistrationCodeFragments codeFragments,
				RegisteredBean registeredBean) {
			super(codeFragments);
			this.registeredBean = registeredBean;
		}

		@Override
		public CodeBlock generateInstanceSupplierCode(GenerationContext generationContext,
				BeanRegistrationCode beanRegistrationCode,
				Executable constructorOrFactoryMethod,
				boolean allowDirectSupplierShortcut) {
			PersistenceManagedTypes persistenceManagedTypes = this.registeredBean.getBeanFactory()
					.getBean(this.registeredBean.getBeanName(), PersistenceManagedTypes.class);
			contributeHints(generationContext.getRuntimeHints(), persistenceManagedTypes.getManagedClassNames());
			GeneratedMethod generatedMethod = beanRegistrationCode.getMethods()
					.add("getInstance", method -> {
						Class<?> beanType = PersistenceManagedTypes.class;
						method.addJavadoc("Get the bean instance for '$L'.",
								this.registeredBean.getBeanName());
						method.addModifiers(Modifier.PRIVATE, Modifier.STATIC);
						method.returns(beanType);
						method.addStatement("$T managedClassNames = $T.of($L)", LIST_OF_STRINGS_TYPE,
								List.class, toCodeBlock(persistenceManagedTypes.getManagedClassNames()));
						method.addStatement("$T managedPackages = $T.of($L)", LIST_OF_STRINGS_TYPE,
								List.class, toCodeBlock(persistenceManagedTypes.getManagedPackages()));
						method.addStatement("return $T.of($L, $L)", beanType, "managedClassNames", "managedPackages");
					});
			return CodeBlock.of("() -> $T.$L()", beanRegistrationCode.getClassName(), generatedMethod.getName());
		}

		private CodeBlock toCodeBlock(List<String> values) {
			return CodeBlock.join(values.stream().map(value -> CodeBlock.of("$S", value)).toList(), ", ");
		}

		private void contributeHints(RuntimeHints hints, List<String> managedClassNames) {
			for (String managedClassName : managedClassNames) {
				try {
					Class<?> managedClass = ClassUtils.forName(managedClassName, null);
					this.bindingRegistrar.registerReflectionHints(hints.reflection(), managedClass);
					contributeEntityListenersHints(hints, managedClass);
					contributeIdClassHints(hints, managedClass);
					contributeConverterHints(hints, managedClass);
				}
				catch (ClassNotFoundException ex) {
					throw new IllegalArgumentException("Failed to instantiate the managed class: " + managedClassName, ex);
				}
			}
		}

		private void contributeEntityListenersHints(RuntimeHints hints, Class<?> managedClass) {
			EntityListeners entityListeners = AnnotationUtils.findAnnotation(managedClass, EntityListeners.class);
			if (entityListeners != null) {
				for (Class<?> entityListener : entityListeners.value()) {
					hints.reflection().registerType(entityListener, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS);
				}
			}
		}

		private void contributeIdClassHints(RuntimeHints hints, Class<?> managedClass) {
			IdClass idClass = AnnotationUtils.findAnnotation(managedClass, IdClass.class);
			if (idClass != null) {
				this.bindingRegistrar.registerReflectionHints(hints.reflection(), idClass.value());
			}
		}

		private void contributeConverterHints(RuntimeHints hints, Class<?> managedClass) {
			Converter converter = AnnotationUtils.findAnnotation(managedClass, Converter.class);
			if (converter != null) {
				hints.reflection().registerType(managedClass, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS);
			}
		}

	}
}

package com.dslplatform.json.runtime;

import com.dslplatform.json.*;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.Callable;

public abstract class BeanAnalyzer {

	private static class LazyBeanDescription implements JsonWriter.WriteObject, JsonReader.ReadObject, JsonReader.BindObject {

		private final DslJson json;
		private final Type type;
		private JsonWriter.WriteObject resolvedWriter;
		private JsonReader.BindObject resolvedBinder;
		private JsonReader.ReadObject resolvedReader;
		volatile BeanDescription resolvedSomewhere;

		LazyBeanDescription(DslJson json, Type type) {
			this.json = json;
			this.type = type;
		}

		private boolean checkSignatureNotFound() {
			int i = 0;
			while (resolvedSomewhere == null && i < 50) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					throw new SerializationException(e);
				}
				i++;
			}
			if (resolvedSomewhere != null) {
				resolvedWriter = resolvedSomewhere;
				resolvedReader = resolvedSomewhere;
				resolvedBinder = resolvedSomewhere;
			}
			return resolvedSomewhere == null;
		}

		@Override
		public Object read(JsonReader reader) throws IOException {
			if (resolvedReader == null) {
				if (checkSignatureNotFound()) {
					final JsonReader.ReadObject tmp = json.tryFindReader(type);
					if (tmp == null || tmp == this) {
						throw new SerializationException("Unable to find reader for " + type);
					}
					resolvedReader = tmp;
				}
			}
			return resolvedReader.read(reader);
		}

		@Override
		public Object bind(final JsonReader reader, final Object instance) throws IOException {
			if (resolvedBinder == null) {
				if (checkSignatureNotFound()) {
					final JsonReader.BindObject tmp = json.tryFindBinder(type);
					if (tmp == null || tmp == this) {
						throw new SerializationException("Unable to find binder for " + type);
					}
					resolvedBinder = tmp;
				}
			}
			return resolvedBinder.bind(reader, instance);
		}

		@Override
		public void write(final JsonWriter writer, final Object value) {
			if (resolvedWriter == null) {
				if (checkSignatureNotFound()) {
					final JsonWriter.WriteObject tmp = json.tryFindWriter(type);
					if (tmp == null || tmp == this) {
						throw new SerializationException("Unable to find writer for " + type);
					}
					resolvedWriter = tmp;
				}
			}
			resolvedWriter.write(writer, value);
		}
	}

	public static final DslJson.ConverterFactory<BeanDescription> CONVERTER = (manifest, dslJson) -> {
		if (manifest instanceof Class<?>) {
			return analyze(manifest, (Class<?>) manifest, dslJson);
		}
		if (manifest instanceof ParameterizedType) {
			final ParameterizedType pt = (ParameterizedType) manifest;
			if (pt.getRawType() instanceof Class<?>) {
				return analyze(manifest, (Class<?>) pt.getRawType(), dslJson);
			}
		}
		return null;
	};

	private static <T> BeanDescription<T, T> analyze(final Type manifest, final Class<T> raw, final DslJson json) {
		if (raw.isArray()
				|| Object.class == manifest
				|| Collection.class.isAssignableFrom(raw)
				|| (raw.getDeclaringClass() != null && (raw.getModifiers() & Modifier.STATIC) == 0)) {
			return null;
		}
		final Callable newInstance;
		Set<Type> currentEncoders = json.getRegisteredEncoders();
		Set<Type> currentDecoders = json.getRegisteredDecoders();
		Set<Type> currentBinders = json.getRegisteredBinders();
		boolean hasEncoder = currentEncoders.contains(manifest);
		boolean hasDecoder = currentDecoders.contains(manifest);
		boolean hasBinder = currentBinders.contains(manifest);
		if ((raw.getModifiers() & Modifier.ABSTRACT) != 0) {
			if (!currentDecoders.contains(manifest)) return null;
			final JsonReader.ReadObject currentReader = json.tryFindReader(manifest);
			if (currentReader instanceof BeanDescription) {
				newInstance = ((BeanDescription)currentReader).newInstance;
			} else return null;
		} else {
			try {
				raw.newInstance();
			} catch (InstantiationException | IllegalAccessException ignore) {
				return null;
			}
			newInstance = raw::newInstance;
		}
		final LazyBeanDescription lazy = new LazyBeanDescription(json, manifest);
		if (!hasEncoder) json.registerWriter(manifest, lazy);
		if (!hasDecoder) json.registerReader(manifest, lazy);
		final LinkedHashMap<String, JsonWriter.WriteObject> foundWrite = new LinkedHashMap<>();
		final LinkedHashMap<String, DecodePropertyInfo<JsonReader.BindObject>> foundRead = new LinkedHashMap<>();
		final HashMap<Type, Type> genericMappings = Generics.analyze(manifest, raw);
		int index = 0;
		for (final Field f : raw.getFields()) {
			if (analyzeField(json, foundWrite, foundRead, f, index, genericMappings)) index++;
		}
		for (final Method m : raw.getMethods()) {
			if (analyzeMethods(m, raw, json, foundWrite, foundRead, index, genericMappings)) index++;
		}
		//TODO: don't register bean if something can't be serialized
		final JsonWriter.WriteObject[] writeProps = foundWrite.values().toArray(new JsonWriter.WriteObject[0]);
		final DecodePropertyInfo<JsonReader.BindObject>[] readProps = foundRead.values().toArray(new DecodePropertyInfo[0]);
		final BeanDescription<T, T> converter = new BeanDescription<T, T>(manifest, newInstance, t -> t, writeProps, readProps, manifest.getTypeName(), true);
		if (!hasEncoder) json.registerWriter(manifest, converter);
		if (!hasDecoder) json.registerReader(manifest, converter);
		if (!hasBinder) json.registerBinder(manifest, converter);
		lazy.resolvedSomewhere = converter;
		return converter;
	}

	private static class SetField implements JsonReader.BindObject {
		private final DslJson json;
		private final Field field;
		private final Type type;
		private JsonReader.ReadObject fieldReader;

		SetField(final DslJson json, final Field field, final Type type) {
			this.json = json;
			this.field = field;
			this.type = type;
		}

		@Override
		public Object bind(final JsonReader reader, final Object instance) throws IOException {
			if (fieldReader == null) {
				fieldReader = json.tryFindReader(type);
				if (fieldReader == null) {
					throw new IOException("Unable to find reader for " + type + " on field " + field.getName() + " of " + field.getDeclaringClass());
				}
			}
			final Object attr = fieldReader.read(reader);
			try {
				field.set(instance, attr);
			} catch (IllegalAccessException e) {
				throw new IOException("Unable to set field " + field.getName() + " of " + field.getDeclaringClass(), e);
			}
			return instance;
		}
	}

	private static boolean analyzeField(
			final DslJson json,
			final LinkedHashMap<String, JsonWriter.WriteObject> foundWrite,
			final LinkedHashMap<String, DecodePropertyInfo<JsonReader.BindObject>> foundRead,
			final Field field,
			final int index,
			final HashMap<Type, Type> genericMappings) {
		if (!canRead(field.getModifiers()) || !canWrite(field.getModifiers())) return false;
		final Type type = field.getGenericType();
		final Type concreteType = Generics.makeConcrete(type, genericMappings);
		final boolean isUnknown = Generics.isUnknownType(type);
		if (isUnknown || json.tryFindWriter(concreteType) != null && json.tryFindReader(concreteType) != null) {
			foundWrite.put(
					field.getName(),
					Settings.createEncoder(
							new Reflection.ReadField(field),
							field.getName(),
							json,
							isUnknown ? null : concreteType));
			foundRead.put(
					field.getName(),
					Settings.createDecoder(
							new Reflection.SetField(field),
							field.getName(),
							json,
							false,
							false,
							index,
							concreteType));
			return true;
		}
		return false;
	}

	private static boolean analyzeMethods(
			final Method mget,
			final Class<?> manifest,
			final DslJson json,
			final LinkedHashMap<String, JsonWriter.WriteObject> foundWrite,
			final LinkedHashMap<String, DecodePropertyInfo<JsonReader.BindObject>> foundRead,
			final int index,
			final HashMap<Type, Type> genericMappings) {
		if (mget.getParameterTypes().length != 0) return false;
		final String setName = mget.getName().startsWith("get") ? "set" + mget.getName().substring(3) : mget.getName();
		final Method mset;
		try {
			mset = manifest.getMethod(setName, mget.getReturnType());
		} catch (NoSuchMethodException ignore) {
			return false;
		}
		final String name = mget.getName().startsWith("get")
				? Character.toLowerCase(mget.getName().charAt(3)) + mget.getName().substring(4)
				: mget.getName();
		if (!canRead(mget.getModifiers()) || !canWrite(mset.getModifiers())) return false;
		if (foundRead.containsKey(name) && foundWrite.containsKey(name)) return false;
		final Type type = mget.getGenericReturnType();
		final Type concreteType = Generics.makeConcrete(type, genericMappings);
		final boolean isUnknown = Generics.isUnknownType(type);
		if (isUnknown || json.tryFindWriter(concreteType) != null && json.tryFindReader(concreteType) != null) {
			foundWrite.put(
					name,
					Settings.createEncoder(
							new Reflection.ReadMethod(mget),
							name,
							json,
							isUnknown ? null : concreteType));
			foundRead.put(
					name,
					Settings.createDecoder(
							new Reflection.SetMethod(mset),
							name,
							json,
							false,
							false,
							index,
							concreteType));
			return true;
		}
		return false;
	}

	private static boolean canRead(final int modifiers) {
		return (modifiers & Modifier.PUBLIC) != 0
				&& (modifiers & Modifier.STATIC) == 0;
	}

	private static boolean canWrite(final int modifiers) {
		return (modifiers & Modifier.PUBLIC) != 0
				&& (modifiers & Modifier.FINAL) == 0
				&& (modifiers & Modifier.STATIC) == 0;
	}
}
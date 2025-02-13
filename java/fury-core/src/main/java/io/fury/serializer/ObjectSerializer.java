/*
 * Copyright 2023 The Fury authors
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fury.serializer;

import static io.fury.type.TypeUtils.getRawType;

import com.google.common.reflect.TypeToken;
import io.fury.Fury;
import io.fury.collection.Tuple2;
import io.fury.collection.Tuple3;
import io.fury.exception.FuryException;
import io.fury.memory.MemoryBuffer;
import io.fury.resolver.ClassInfo;
import io.fury.resolver.ClassInfoCache;
import io.fury.resolver.ClassResolver;
import io.fury.resolver.RefResolver;
import io.fury.type.Descriptor;
import io.fury.type.DescriptorGrouper;
import io.fury.type.FinalObjectTypeStub;
import io.fury.type.GenericType;
import io.fury.type.Generics;
import io.fury.util.Platform;
import io.fury.util.ReflectionUtils;
import io.fury.util.UnsafeFieldAccessor;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * A schema-consistent serializer used only for java serialization.
 *
 * <ul>
 *   <li>non-public class
 *   <li>non-static class
 *   <li>lambda
 *   <li>inner class
 *   <li>local class
 *   <li>anonymous class
 *   <li>class that can't be handled by other serializers or codegen-based serializers
 * </ul>
 *
 * @author chaokunyang
 */
// TODO(chaokunyang) support generics optimization for {@code SomeClass<T>}
@SuppressWarnings({"UnstableApiUsage"})
public final class ObjectSerializer<T> extends Serializer<T> {
  private final RefResolver refResolver;
  private final ClassResolver classResolver;
  private final FinalTypeField[] finalFields;
  /**
   * Whether write class def for non-inner final types.
   *
   * @see ClassResolver#isFinal(Class)
   */
  private final boolean[] isFinal;

  private final GenericTypeField[] otherFields;
  private final GenericTypeField[] containerFields;
  private final Constructor<T> constructor;
  private final int classVersionHash;

  public ObjectSerializer(Fury fury, Class<T> cls) {
    this(fury, cls, true);
  }

  public ObjectSerializer(Fury fury, Class<T> cls, boolean resolveParent) {
    super(fury, cls);
    this.refResolver = fury.getRefResolver();
    this.classResolver = fury.getClassResolver();
    // avoid recursive building serializers.
    // Use `setSerializerIfAbsent` to avoid overwriting existing serializer for class when used
    // as data serializer.
    classResolver.setSerializerIfAbsent(cls, this);
    this.constructor = ReflectionUtils.getExecutableNoArgConstructor(cls);
    Collection<Descriptor> descriptors =
        fury.getClassResolver().getAllDescriptorsMap(cls, resolveParent).values();
    if (fury.checkClassVersion()) {
      classVersionHash = computeVersionHash(descriptors);
    } else {
      classVersionHash = 0;
    }
    DescriptorGrouper descriptorGrouper =
        DescriptorGrouper.createDescriptorGrouper(descriptors, false, fury.compressNumber());
    Tuple3<Tuple2<FinalTypeField[], boolean[]>, GenericTypeField[], GenericTypeField[]> infos =
        buildFieldInfos(fury, descriptorGrouper);
    finalFields = infos.f0.f0;
    isFinal = infos.f0.f1;
    otherFields = infos.f1;
    containerFields = infos.f2;
  }

  static Tuple3<Tuple2<FinalTypeField[], boolean[]>, GenericTypeField[], GenericTypeField[]>
      buildFieldInfos(Fury fury, DescriptorGrouper grouper) {
    // When a type is both Collection/Map and final, add it to collection/map fields to keep
    // consistent with jit.
    Collection<Descriptor> primitives = grouper.getPrimitiveDescriptors();
    Collection<Descriptor> boxed = grouper.getBoxedDescriptors();
    Collection<Descriptor> finals = grouper.getFinalDescriptors();
    FinalTypeField[] finalFields =
        new FinalTypeField[primitives.size() + boxed.size() + finals.size()];
    int cnt = 0;
    for (Descriptor d : primitives) {
      finalFields[cnt++] = buildFinalTypeField(fury, d);
    }
    for (Descriptor d : boxed) {
      finalFields[cnt++] = buildFinalTypeField(fury, d);
    }
    // TODO(chaokunyang) Support Pojo<T> generics besides Map/Collection subclass
    //  when it's supported in BaseObjectCodecBuilder.
    for (Descriptor d : finals) {
      finalFields[cnt++] = buildFinalTypeField(fury, d);
    }
    boolean[] isFinal = new boolean[finalFields.length];
    for (int i = 0; i < isFinal.length; i++) {
      ClassInfo classInfo = finalFields[i].classInfo;
      isFinal[i] = classInfo != null && fury.getClassResolver().isFinal(classInfo.getCls());
    }
    cnt = 0;
    GenericTypeField[] otherFields = new GenericTypeField[grouper.getOtherDescriptors().size()];
    for (Descriptor descriptor : grouper.getOtherDescriptors()) {
      GenericTypeField genericTypeField =
          new GenericTypeField(
              descriptor.getRawType(),
              descriptor.getDeclaringClass() + "." + descriptor.getName(),
              descriptor.getField() != null ? new UnsafeFieldAccessor(descriptor.getField()) : null,
              fury);
      otherFields[cnt++] = genericTypeField;
    }
    cnt = 0;
    Collection<Descriptor> collections = grouper.getCollectionDescriptors();
    Collection<Descriptor> maps = grouper.getMapDescriptors();
    GenericTypeField[] containerFields = new GenericTypeField[collections.size() + maps.size()];
    for (Descriptor d : collections) {
      containerFields[cnt++] = buildContainerField(fury, d);
    }
    for (Descriptor d : maps) {
      containerFields[cnt++] = buildContainerField(fury, d);
    }
    return Tuple3.of(Tuple2.of(finalFields, isFinal), otherFields, containerFields);
  }

  private static FinalTypeField buildFinalTypeField(Fury fury, Descriptor d) {
    return new FinalTypeField(
        d.getRawType(),
        d.getDeclaringClass() + "." + d.getName(),
        // `d.getField()` will be null when peer class doesn't have this field.
        d.getField() != null ? new UnsafeFieldAccessor(d.getField()) : null,
        fury);
  }

  private static GenericTypeField buildContainerField(Fury fury, Descriptor d) {
    return new GenericTypeField(
        d.getTypeToken(),
        d.getDeclaringClass() + "." + d.getName(),
        d.getField() != null ? new UnsafeFieldAccessor(d.getField()) : null,
        fury);
  }

  @Override
  public void write(MemoryBuffer buffer, T value) {
    Fury fury = this.fury;
    RefResolver refResolver = this.refResolver;
    ClassResolver classResolver = this.classResolver;
    if (fury.checkClassVersion()) {
      buffer.writeInt(classVersionHash);
    }
    // write order: primitive,boxed,final,other,collection,map
    writeFinalFields(buffer, value, fury, refResolver, classResolver);
    for (GenericTypeField fieldInfo : otherFields) {
      UnsafeFieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      Object fieldValue = fieldAccessor.getObject(value);
      if (fieldInfo.trackingRef) {
        fury.writeRef(buffer, fieldValue, fieldInfo.classInfoCache);
      } else {
        fury.writeNullable(buffer, fieldValue, fieldInfo.classInfoCache);
      }
    }
    writeContainerFields(buffer, value, fury, refResolver, classResolver);
  }

  private void writeFinalFields(
      MemoryBuffer buffer,
      T value,
      Fury fury,
      RefResolver refResolver,
      ClassResolver classResolver) {
    FinalTypeField[] finalFields = this.finalFields;
    boolean metaContextShareEnabled = fury.getConfig().isMetaContextShareEnabled();
    for (int i = 0; i < finalFields.length; i++) {
      FinalTypeField fieldInfo = finalFields[i];
      UnsafeFieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      short classId = fieldInfo.classId;
      if (writePrimitiveFieldValueFailed(fury, buffer, value, fieldAccessor, classId)) {
        Object fieldValue = fieldAccessor.getObject(value);
        if (writeBasicObjectFieldValueFailed(fury, buffer, fieldValue, classId)) {
          Serializer<Object> serializer = fieldInfo.classInfo.getSerializer();
          if (!metaContextShareEnabled || isFinal[i]) {
            // whether tracking ref is recorded in `fieldInfo.serializer`, so it's still
            // consistent with jit serializer.
            fury.writeRef(buffer, fieldValue, serializer);
          } else {
            if (serializer.needToWriteRef()) {
              if (!refResolver.writeRefOrNull(buffer, fieldValue)) {
                classResolver.writeClass(buffer, fieldInfo.classInfo);
                // No generics for field, no need to update `depth`.
                serializer.write(buffer, fieldValue);
              }
            } else {
              fury.writeNullable(buffer, fieldValue, fieldInfo.classInfo);
            }
          }
        }
      }
    }
  }

  private void writeContainerFields(
      MemoryBuffer buffer,
      T value,
      Fury fury,
      RefResolver refResolver,
      ClassResolver classResolver) {
    Generics generics = fury.getGenerics();
    for (GenericTypeField fieldInfo : containerFields) {
      UnsafeFieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      Object fieldValue = fieldAccessor.getObject(value);
      writeContainerFieldValue(
          fury, refResolver, classResolver, generics, fieldInfo, buffer, fieldValue);
    }
  }

  protected static void writeContainerFieldValue(
      Fury fury,
      RefResolver refResolver,
      ClassResolver classResolver,
      Generics generics,
      GenericTypeField fieldInfo,
      MemoryBuffer buffer,
      Object fieldValue) {
    if (fieldInfo.trackingRef) {
      if (!refResolver.writeRefOrNull(buffer, fieldValue)) {
        ClassInfo classInfo =
            classResolver.getClassInfo(fieldValue.getClass(), fieldInfo.classInfoCache);
        generics.pushGenericType(fieldInfo.genericType);
        fury.writeNonRef(buffer, fieldValue, classInfo);
        generics.popGenericType();
      }
    } else {
      if (fieldValue == null) {
        buffer.writeByte(Fury.NULL_FLAG);
      } else {
        buffer.writeByte(Fury.NOT_NULL_VALUE_FLAG);
        generics.pushGenericType(fieldInfo.genericType);
        fury.writeNonRef(
            buffer,
            fieldValue,
            classResolver.getClassInfo(fieldValue.getClass(), fieldInfo.classInfoCache));
        generics.popGenericType();
      }
    }
  }

  @Override
  public T read(MemoryBuffer buffer) {
    T obj = newBean(constructor, type);
    refResolver.reference(obj);
    return readAndSetFields(buffer, obj);
  }

  public T readAndSetFields(MemoryBuffer buffer, T obj) {
    Fury fury = this.fury;
    RefResolver refResolver = this.refResolver;
    ClassResolver classResolver = this.classResolver;
    if (fury.checkClassVersion()) {
      int hash = buffer.readInt();
      checkClassVersion(fury, hash, classVersionHash);
    }
    // read order: primitive,boxed,final,other,collection,map
    FinalTypeField[] finalFields = this.finalFields;
    boolean metaContextShareEnabled = fury.getConfig().isMetaContextShareEnabled();
    for (int i = 0; i < finalFields.length; i++) {
      FinalTypeField fieldInfo = finalFields[i];
      boolean isFinal = !metaContextShareEnabled || this.isFinal[i];
      UnsafeFieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      short classId = fieldInfo.classId;
      if (readPrimitiveFieldValueFailed(fury, buffer, obj, fieldAccessor, classId)
          && readBasicObjectFieldValueFailed(fury, buffer, obj, fieldAccessor, classId)) {
        Object fieldValue =
            readFinalObjectFieldValue(fury, refResolver, classResolver, fieldInfo, isFinal, buffer);
        fieldAccessor.putObject(obj, fieldValue);
      }
    }
    for (GenericTypeField fieldInfo : otherFields) {
      Object fieldValue = readOtherFieldValue(fury, fieldInfo, buffer);
      UnsafeFieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      fieldAccessor.putObject(obj, fieldValue);
    }
    Generics generics = fury.getGenerics();
    for (GenericTypeField fieldInfo : containerFields) {
      Object fieldValue = readContainerFieldValue(fury, generics, fieldInfo, buffer);
      UnsafeFieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      fieldAccessor.putObject(obj, fieldValue);
    }
    return obj;
  }

  /**
   * Read final object field value. Note that primitive field value can't be read by this method,
   * because primitive field doesn't write null flag.
   */
  static Object readFinalObjectFieldValue(
      Fury fury,
      RefResolver refResolver,
      ClassResolver classResolver,
      FinalTypeField fieldInfo,
      boolean isFinal,
      MemoryBuffer buffer) {
    Serializer<Object> serializer = fieldInfo.classInfo.getSerializer();
    Object fieldValue;
    if (isFinal) {
      // whether tracking ref is recorded in `fieldInfo.serializer`, so it's still
      // consistent with jit serializer.
      fieldValue = fury.readRef(buffer, serializer);
    } else {
      if (serializer.needToWriteRef()) {
        int nextReadRefId = refResolver.tryPreserveRefId(buffer);
        if (nextReadRefId >= Fury.NOT_NULL_VALUE_FLAG) {
          classResolver.readClassInfo(buffer, fieldInfo.classInfo);
          fieldValue = serializer.read(buffer);
          refResolver.setReadObject(nextReadRefId, fieldValue);
        } else {
          fieldValue = refResolver.getReadObject();
        }
      } else {
        byte headFlag = buffer.readByte();
        if (headFlag == Fury.NULL_FLAG) {
          fieldValue = null;
        } else {
          classResolver.readClassInfo(buffer, fieldInfo.classInfo);
          fieldValue = serializer.read(buffer);
        }
      }
    }
    return fieldValue;
  }

  static Object readOtherFieldValue(Fury fury, GenericTypeField fieldInfo, MemoryBuffer buffer) {
    Object fieldValue;
    if (fieldInfo.trackingRef) {
      fieldValue = fury.readRef(buffer, fieldInfo.classInfoCache);
    } else {
      byte headFlag = buffer.readByte();
      if (headFlag == Fury.NULL_FLAG) {
        fieldValue = null;
      } else {
        fieldValue = fury.readNonRef(buffer, fieldInfo.classInfoCache);
      }
    }
    return fieldValue;
  }

  static Object readContainerFieldValue(
      Fury fury, Generics generics, GenericTypeField fieldInfo, MemoryBuffer buffer) {
    Object fieldValue;
    if (fieldInfo.trackingRef) {
      generics.pushGenericType(fieldInfo.genericType);
      fieldValue = fury.readRef(buffer, fieldInfo.classInfoCache);
      generics.popGenericType();
    } else {
      byte headFlag = buffer.readByte();
      if (headFlag == Fury.NULL_FLAG) {
        fieldValue = null;
      } else {
        generics.pushGenericType(fieldInfo.genericType);
        fieldValue = fury.readNonRef(buffer, fieldInfo.classInfoCache);
        generics.popGenericType();
      }
    }
    return fieldValue;
  }

  static boolean writePrimitiveFieldValueFailed(
      Fury fury,
      MemoryBuffer buffer,
      Object targetObject,
      UnsafeFieldAccessor fieldAccessor,
      short classId) {
    switch (classId) {
      case ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID:
        buffer.writeBoolean(fieldAccessor.getBoolean(targetObject));
        return false;
      case ClassResolver.PRIMITIVE_BYTE_CLASS_ID:
        buffer.writeByte(fieldAccessor.getByte(targetObject));
        return false;
      case ClassResolver.PRIMITIVE_CHAR_CLASS_ID:
        buffer.writeChar(fieldAccessor.getChar(targetObject));
        return false;
      case ClassResolver.PRIMITIVE_SHORT_CLASS_ID:
        buffer.writeShort(fieldAccessor.getShort(targetObject));
        return false;
      case ClassResolver.PRIMITIVE_INT_CLASS_ID:
        {
          int fieldValue = fieldAccessor.getInt(targetObject);
          if (fury.compressNumber()) {
            buffer.writeVarInt(fieldValue);
          } else {
            buffer.writeInt(fieldValue);
          }
          return false;
        }
      case ClassResolver.PRIMITIVE_FLOAT_CLASS_ID:
        buffer.writeFloat(fieldAccessor.getFloat(targetObject));
        return false;
      case ClassResolver.PRIMITIVE_LONG_CLASS_ID:
        {
          long fieldValue = fieldAccessor.getLong(targetObject);
          if (fury.compressNumber()) {
            buffer.writeVarLong(fieldValue);
          } else {
            buffer.writeLong(fieldValue);
          }
          return false;
        }
      case ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID:
        buffer.writeDouble(fieldAccessor.getDouble(targetObject));
        return false;
      default:
        return true;
    }
  }

  /**
   * Write field value to buffer.
   *
   * @return true if field value isn't written by this function.
   */
  static boolean writeBasicObjectFieldValueFailed(
      Fury fury, MemoryBuffer buffer, Object fieldValue, short classId) {
    if (!fury.isBasicTypesRefIgnored()) {
      return true; // let common path handle this.
    }
    // add time types serialization here.
    switch (classId) {
      case ClassResolver.STRING_CLASS_ID: // fastpath for string.
        fury.writeJavaStringRef(buffer, (String) (fieldValue));
        return false;
      case ClassResolver.BOOLEAN_CLASS_ID:
        {
          if (fieldValue == null) {
            buffer.writeByte(Fury.NULL_FLAG);
          } else {
            buffer.writeByte(Fury.NOT_NULL_VALUE_FLAG);
            buffer.writeBoolean((Boolean) (fieldValue));
          }
          return false;
        }
      case ClassResolver.BYTE_CLASS_ID:
        {
          if (fieldValue == null) {
            buffer.writeByte(Fury.NULL_FLAG);
          } else {
            buffer.writeByte(Fury.NOT_NULL_VALUE_FLAG);
            buffer.writeByte((Byte) (fieldValue));
          }
          return false;
        }
      case ClassResolver.CHAR_CLASS_ID:
        {
          if (fieldValue == null) {
            buffer.writeByte(Fury.NULL_FLAG);
          } else {
            buffer.writeByte(Fury.NOT_NULL_VALUE_FLAG);
            buffer.writeChar((Character) (fieldValue));
          }
          return false;
        }
      case ClassResolver.SHORT_CLASS_ID:
        {
          if (fieldValue == null) {
            buffer.writeByte(Fury.NULL_FLAG);
          } else {
            buffer.writeByte(Fury.NOT_NULL_VALUE_FLAG);
            buffer.writeShort((Short) (fieldValue));
          }
          return false;
        }
      case ClassResolver.INTEGER_CLASS_ID:
        {
          if (fieldValue == null) {
            buffer.writeByte(Fury.NULL_FLAG);
          } else {
            buffer.writeByte(Fury.NOT_NULL_VALUE_FLAG);
            if (fury.compressNumber()) {
              buffer.writeVarInt((Integer) (fieldValue));
            } else {
              buffer.writeInt((Integer) (fieldValue));
            }
          }
          return false;
        }
      case ClassResolver.FLOAT_CLASS_ID:
        {
          if (fieldValue == null) {
            buffer.writeByte(Fury.NULL_FLAG);
          } else {
            buffer.writeByte(Fury.NOT_NULL_VALUE_FLAG);
            buffer.writeFloat((Float) (fieldValue));
          }
          return false;
        }
      case ClassResolver.LONG_CLASS_ID:
        {
          if (fieldValue == null) {
            buffer.writeByte(Fury.NULL_FLAG);
          } else {
            buffer.writeByte(Fury.NOT_NULL_VALUE_FLAG);
            if (fury.compressNumber()) {
              buffer.writeVarLong((Long) (fieldValue));
            } else {
              buffer.writeLong((Long) (fieldValue));
            }
          }
          return false;
        }
      case ClassResolver.DOUBLE_CLASS_ID:
        {
          if (fieldValue == null) {
            buffer.writeByte(Fury.NULL_FLAG);
          } else {
            buffer.writeByte(Fury.NOT_NULL_VALUE_FLAG);
            buffer.writeDouble((Double) (fieldValue));
          }
          return false;
        }
      default:
        return true;
    }
  }

  /**
   * Read a primitive value from buffer and set it to field referenced by <code>fieldAccessor</code>
   * of <code>targetObject</code>.
   *
   * @return true if <code>classId</code> is not a primitive type id.
   */
  static boolean readPrimitiveFieldValueFailed(
      Fury fury,
      MemoryBuffer buffer,
      Object targetObject,
      UnsafeFieldAccessor fieldAccessor,
      short classId) {
    switch (classId) {
      case ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID:
        fieldAccessor.putBoolean(targetObject, buffer.readBoolean());
        return false;
      case ClassResolver.PRIMITIVE_BYTE_CLASS_ID:
        fieldAccessor.putByte(targetObject, buffer.readByte());
        return false;
      case ClassResolver.PRIMITIVE_CHAR_CLASS_ID:
        fieldAccessor.putChar(targetObject, buffer.readChar());
        return false;
      case ClassResolver.PRIMITIVE_SHORT_CLASS_ID:
        fieldAccessor.putShort(targetObject, buffer.readShort());
        return false;
      case ClassResolver.PRIMITIVE_INT_CLASS_ID:
        if (fury.compressNumber()) {
          fieldAccessor.putInt(targetObject, buffer.readVarInt());
        } else {
          fieldAccessor.putInt(targetObject, buffer.readInt());
        }
        return false;
      case ClassResolver.PRIMITIVE_FLOAT_CLASS_ID:
        fieldAccessor.putFloat(targetObject, buffer.readFloat());
        return false;
      case ClassResolver.PRIMITIVE_LONG_CLASS_ID:
        if (fury.compressNumber()) {
          fieldAccessor.putLong(targetObject, buffer.readVarLong());
        } else {
          fieldAccessor.putLong(targetObject, buffer.readLong());
        }
        return false;
      case ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID:
        fieldAccessor.putDouble(targetObject, buffer.readDouble());
        return false;
      case ClassResolver.STRING_CLASS_ID:
        fieldAccessor.putObject(targetObject, fury.readJavaStringRef(buffer));
        return false;
      default:
        {
          return true;
        }
    }
  }

  static boolean readBasicObjectFieldValueFailed(
      Fury fury,
      MemoryBuffer buffer,
      Object targetObject,
      UnsafeFieldAccessor fieldAccessor,
      short classId) {
    if (!fury.isBasicTypesRefIgnored()) {
      return true; // let common path handle this.
    }
    // add time types serialization here.
    switch (classId) {
      case ClassResolver.STRING_CLASS_ID: // fastpath for string.
        fieldAccessor.putObject(targetObject, fury.readJavaStringRef(buffer));
        return false;
      case ClassResolver.BOOLEAN_CLASS_ID:
        {
          if (buffer.readByte() == Fury.NULL_FLAG) {
            fieldAccessor.putObject(targetObject, null);
          } else {
            fieldAccessor.putObject(targetObject, buffer.readBoolean());
          }
          return false;
        }
      case ClassResolver.BYTE_CLASS_ID:
        {
          if (buffer.readByte() == Fury.NULL_FLAG) {
            fieldAccessor.putObject(targetObject, null);
          } else {
            fieldAccessor.putObject(targetObject, buffer.readByte());
          }
          return false;
        }
      case ClassResolver.CHAR_CLASS_ID:
        {
          if (buffer.readByte() == Fury.NULL_FLAG) {
            fieldAccessor.putObject(targetObject, null);
          } else {
            fieldAccessor.putObject(targetObject, buffer.readChar());
          }
          return false;
        }
      case ClassResolver.SHORT_CLASS_ID:
        {
          if (buffer.readByte() == Fury.NULL_FLAG) {
            fieldAccessor.putObject(targetObject, null);
          } else {
            fieldAccessor.putObject(targetObject, buffer.readShort());
          }
          return false;
        }
      case ClassResolver.INTEGER_CLASS_ID:
        {
          if (buffer.readByte() == Fury.NULL_FLAG) {
            fieldAccessor.putObject(targetObject, null);
          } else {
            if (fury.compressNumber()) {
              fieldAccessor.putObject(targetObject, buffer.readVarInt());
            } else {
              fieldAccessor.putObject(targetObject, buffer.readInt());
            }
          }
          return false;
        }
      case ClassResolver.FLOAT_CLASS_ID:
        {
          if (buffer.readByte() == Fury.NULL_FLAG) {
            fieldAccessor.putObject(targetObject, null);
          } else {
            fieldAccessor.putObject(targetObject, buffer.readFloat());
          }
          return false;
        }
      case ClassResolver.LONG_CLASS_ID:
        {
          if (buffer.readByte() == Fury.NULL_FLAG) {
            fieldAccessor.putObject(targetObject, null);
          } else {
            if (fury.compressNumber()) {
              fieldAccessor.putObject(targetObject, buffer.readVarLong());
            } else {
              fieldAccessor.putObject(targetObject, buffer.readLong());
            }
          }
          return false;
        }
      case ClassResolver.DOUBLE_CLASS_ID:
        {
          if (buffer.readByte() == Fury.NULL_FLAG) {
            fieldAccessor.putObject(targetObject, null);
          } else {
            fieldAccessor.putObject(targetObject, buffer.readDouble());
          }
          return false;
        }
      default:
        return true;
    }
  }

  static <T> T newBean(Constructor<T> constructor, Class<T> type) {
    if (constructor != null) {
      try {
        return constructor.newInstance();
      } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
        Platform.throwException(e);
      }
    }
    return Platform.newInstance(type);
  }

  static class InternalFieldInfo {
    protected final short classId;

    protected final String qualifiedFieldName;
    protected final UnsafeFieldAccessor fieldAccessor;

    private InternalFieldInfo(
        short classId, String qualifiedFieldName, UnsafeFieldAccessor fieldAccessor) {
      this.classId = classId;
      this.qualifiedFieldName = qualifiedFieldName;
      this.fieldAccessor = fieldAccessor;
    }

    @Override
    public String toString() {
      return "InternalFieldInfo{"
          + "classId="
          + classId
          + ", fieldName="
          + qualifiedFieldName
          + ", field="
          + (fieldAccessor != null ? fieldAccessor.getField() : null)
          + '}';
    }
  }

  static final class FinalTypeField extends InternalFieldInfo {
    final ClassInfo classInfo;

    private FinalTypeField(
        Class<?> type, String fieldName, UnsafeFieldAccessor accessor, Fury fury) {
      super(getRegisteredClassId(fury, type), fieldName, accessor);
      // invoke `copy` to avoid ObjectSerializer construct clear serializer by `clearSerializer`.
      if (type == FinalObjectTypeStub.class) {
        // `FinalObjectTypeStub` has no fields, using its `classInfo`
        // will make deserialization failed.
        classInfo = null;
      } else {
        classInfo = fury.getClassResolver().getClassInfo(type);
      }
    }
  }

  static final class GenericTypeField extends InternalFieldInfo {
    protected final GenericType genericType;
    protected final ClassInfoCache classInfoCache;
    protected final boolean trackingRef;

    private GenericTypeField(
        Class<?> cls, String qualifiedFieldName, UnsafeFieldAccessor accessor, Fury fury) {
      super(getRegisteredClassId(fury, cls), qualifiedFieldName, accessor);
      // TODO support generics <T> in Pojo<T>, see ComplexObjectSerializer.getGenericTypes
      genericType = fury.getClassResolver().buildGenericType(cls);
      classInfoCache = fury.getClassResolver().nilClassInfoCache();
      trackingRef = fury.getClassResolver().needToWriteRef(cls);
    }

    private GenericTypeField(
        TypeToken<?> typeToken,
        String qualifiedFieldName,
        UnsafeFieldAccessor accessor,
        Fury fury) {
      super(getRegisteredClassId(fury, getRawType(typeToken)), qualifiedFieldName, accessor);
      // TODO support generics <T> in Pojo<T>, see ComplexObjectSerializer.getGenericTypes
      genericType = fury.getClassResolver().buildGenericType(typeToken);
      classInfoCache = fury.getClassResolver().nilClassInfoCache();
      trackingRef = fury.getClassResolver().needToWriteRef(getRawType(typeToken));
    }

    @Override
    public String toString() {
      return "GenericTypeField{"
          + "genericType="
          + genericType
          + ", classId="
          + classId
          + ", qualifiedFieldName="
          + qualifiedFieldName
          + ", field="
          + (fieldAccessor != null ? fieldAccessor.getField() : null)
          + '}';
    }
  }

  private static short getRegisteredClassId(Fury fury, Class<?> cls) {
    Short classId = fury.getClassResolver().getRegisteredClassId(cls);
    return classId == null ? ClassResolver.NO_CLASS_ID : classId;
  }

  public static int computeVersionHash(Collection<Descriptor> descriptors) {
    // TODO(chaokunyang) use murmurhash
    List<Integer> list = new ArrayList<>();
    for (Descriptor d : descriptors) {
      Integer integer = Objects.hash(d.getName(), d.getRawType().getName(), d.getDeclaringClass());
      list.add(integer);
    }
    return list.hashCode();
  }

  public static void checkClassVersion(Fury fury, int readHash, int classVersionHash) {
    if (readHash != classVersionHash) {
      throw new FuryException(
          String.format(
              "Read class %s version %s is not consistent with %s",
              fury.getClassResolver().getCurrentReadClass(), readHash, classVersionHash));
    }
  }
}

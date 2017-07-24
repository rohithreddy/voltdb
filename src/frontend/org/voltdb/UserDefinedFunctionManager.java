/* This file is part of VoltDB.
 * Copyright (C) 2008-2017 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.ByteBuffer;

import org.voltdb.catalog.CatalogMap;
import org.voltdb.catalog.Function;
import org.voltdb.common.Constants;
import org.voltdb.types.GeographyPointValue;
import org.voltdb.types.GeographyValue;
import org.voltdb.types.TimestampType;
import org.voltdb.types.VoltDecimalHelper;
import org.voltdb.utils.SerializationHelper;

import com.google_voltpatches.common.collect.ImmutableMap;

public class UserDefinedFunctionManager {

    static final String ORGVOLTDB_FUNCCNAME_ERROR_FMT =
            "VoltDB does not support function classes with package names " +
            "that are prefixed with \"org.voltdb\". Please use a different " +
            "package name and retry. The class name was %s.";
    static final String UNABLETOLOAD_ERROR_FMT =
            "VoltDB was unable to load a function (%s) which was expected to be " +
            "in the catalog jarfile and will now exit.";

    ImmutableMap<Integer, UserDefinedFunctionRunner> m_udfs = ImmutableMap.<Integer, UserDefinedFunctionRunner>builder().build();

    public UserDefinedFunctionRunner getFunctionRunnerById(int functionId) {
        return m_udfs.get(functionId);
    }

    public void loadFunctions(CatalogContext catalogContext) {
        final CatalogMap<Function> catalogFunctions = catalogContext.database.getFunctions();
        ImmutableMap.Builder<Integer, UserDefinedFunctionRunner> builder = ImmutableMap.<Integer, UserDefinedFunctionRunner>builder();
        for (final Function catalogFunction : catalogFunctions) {
            final String className = catalogFunction.getClassname();
            Class<?> funcClass = null;
            try {
                funcClass = catalogContext.classForProcedureOrUDF(className);
            }
            catch (final ClassNotFoundException e) {
                if (className.startsWith("org.voltdb.")) {
                    String msg = String.format(ORGVOLTDB_FUNCCNAME_ERROR_FMT, className);
                    VoltDB.crashLocalVoltDB(msg, false, null);
                }
                else {
                    String msg = String.format(UNABLETOLOAD_ERROR_FMT, className);
                    VoltDB.crashLocalVoltDB(msg, false, null);
                }
            }
            Object funcInstance = null;
            try {
                funcInstance = funcClass.newInstance();
            }
            catch (InstantiationException | IllegalAccessException e) {
                throw new RuntimeException(String.format("Error instantiating function \"%s\"", className), e);
            }
            assert(funcInstance != null);
            builder.put(catalogFunction.getFunctionid(), new UserDefinedFunctionRunner(catalogFunction, funcInstance));
        }
        m_udfs = builder.build();
    }


    public static class UserDefinedFunctionRunner {
        final String m_functionName;
        final int m_functionId;
        final Object m_functionInstance;
        Method m_functionMethod;
        final VoltType[] m_paramTypes;
        final boolean[] m_boxUpByteArray;
        final VoltType m_returnType;
        final int m_paramCount;

        static final int VAR_LEN_SIZE = Integer.SIZE/8;

        public UserDefinedFunctionRunner(Function catalogFunction, Object funcInstance) {
            m_functionName = catalogFunction.getFunctionname();
            m_functionId = catalogFunction.getFunctionid();
            m_functionInstance = funcInstance;
            m_functionMethod = null;
            String methodName = catalogFunction.getMethodname();
            for (final Method m : m_functionInstance.getClass().getDeclaredMethods()) {
                if (m.getName().equals(methodName)) {
                    if (! Modifier.isPublic(m.getModifiers())) {
                        continue;
                    }
                    if (Modifier.isStatic(m.getModifiers())) {
                        continue;
                    }
                    if (m.getReturnType().equals(Void.TYPE)) {
                        continue;
                    }
                    m_functionMethod = m;
                    break;
                }
            }
            if (m_functionMethod == null) {
                throw new RuntimeException(
                        String.format("Error loading function %s: cannot find the %s() method.",
                                m_functionName, methodName));
            }
            Class<?>[] paramTypeClasses = m_functionMethod.getParameterTypes();
            m_paramCount = paramTypeClasses.length;
            m_paramTypes = new VoltType[m_paramCount];
            m_boxUpByteArray = new boolean[m_paramCount];
            for (int i = 0; i < m_paramCount; i++) {
                m_paramTypes[i] = VoltType.typeFromClass(paramTypeClasses[i]);
                m_boxUpByteArray[i] = paramTypeClasses[i] == Byte[].class;
            }
            m_returnType = VoltType.typeFromClass(m_functionMethod.getReturnType());
        }

        private static byte[] readVarbinary(ByteBuffer buffer) {
            // Sanity check the size against the remaining buffer size.
            if (VAR_LEN_SIZE > buffer.remaining()) {
                throw new RuntimeException(String.format(
                        "Can't read varbinary size as %d byte integer " +
                        "from buffer with %d bytes remaining.",
                        VAR_LEN_SIZE, buffer.remaining()));
            }
            final int len = buffer.getInt();
            if (len == VoltTable.NULL_STRING_INDICATOR) {
                return null;
            }
            if (len < 0) {
                throw new RuntimeException("Invalid object length.");
            }
            byte[] data = new byte[len];
            buffer.get(data);
            return data;
        }

        public static Object getValueFromBuffer(ByteBuffer buffer, VoltType type) {
            switch (type) {
            case TINYINT:
                return buffer.get();
            case SMALLINT:
                return buffer.getShort();
            case INTEGER:
                return buffer.getInt();
            case BIGINT:
                return buffer.getLong();
            case FLOAT:
                return buffer.getDouble();
            case STRING:
                byte[] stringAsBytes = readVarbinary(buffer);
                if (stringAsBytes == null) {
                    return null;
                }
                return new String(stringAsBytes, VoltTable.ROWDATA_ENCODING);
            case VARBINARY:
                return readVarbinary(buffer);
            case TIMESTAMP:
                long timestampValue = buffer.getLong();
                if (timestampValue == Long.MIN_VALUE) {
                    return null;
                }
                return new TimestampType(timestampValue);
            case DECIMAL:
                return VoltDecimalHelper.deserializeBigDecimal(buffer);
            case GEOGRAPHY_POINT:
                return GeographyPointValue.unflattenFromBuffer(buffer);
            case GEOGRAPHY:
                byte[] geographyValueBytes = readVarbinary(buffer);
                if (geographyValueBytes == null) {
                    return null;
                }
                return GeographyValue.unflattenFromBuffer(ByteBuffer.wrap(geographyValueBytes));
            default:
                throw new RuntimeException("Cannot read from VoltDB UDF buffer.");
            }
        }

        public static void writeValueToBuffer(ByteBuffer buffer, VoltType type, Object value) throws IOException {
            buffer.put(type.getValue());
            if (VoltType.isVoltNullValue(value)) {
                value = type.getNullValue();
                if (value == VoltType.NULL_TIMESTAMP) {
                    buffer.putLong(VoltType.NULL_BIGINT);  // corresponds to EE value.h isNull()
                    return;
                }
                else if (value == VoltType.NULL_STRING_OR_VARBINARY) {
                    buffer.putInt(VoltType.NULL_STRING_LENGTH);
                    return;
                }
                else if (value == VoltType.NULL_DECIMAL) {
                    VoltDecimalHelper.serializeNull(buffer);
                    return;
                }
                else if (value == VoltType.NULL_POINT) {
                    GeographyPointValue.serializeNull(buffer);
                    return;
                }
                else if (value == VoltType.NULL_GEOGRAPHY) {
                    buffer.putInt(VoltType.NULL_STRING_LENGTH);
                    return;
                }
            }
            switch (type) {
            case TINYINT:
                buffer.put((Byte)value);
                break;
            case SMALLINT:
                buffer.putShort((Short)value);
                break;
            case INTEGER:
                buffer.putInt((Integer) value);
                break;
            case BIGINT:
                buffer.putLong((Long) value);
                break;
            case FLOAT:
                buffer.putDouble(((Double) value).doubleValue());
                break;
            case STRING:
                byte[] stringAsBytes = ((String)value).getBytes(Constants.UTF8ENCODING);
                SerializationHelper.writeVarbinary(stringAsBytes, buffer);
                break;
            case VARBINARY:
                if (value instanceof byte[]) {
                    SerializationHelper.writeVarbinary(((byte[])value), buffer);
                }
                else if (value instanceof Byte[]) {
                    SerializationHelper.writeVarbinary(((Byte[])value), buffer);
                }
                break;
            case TIMESTAMP:
                buffer.putLong(((TimestampType)value).getTime());
                break;
            case DECIMAL:
                VoltDecimalHelper.serializeBigDecimal((BigDecimal)value, buffer);
                break;
            case GEOGRAPHY_POINT:
                ((GeographyPointValue)value).flattenToBuffer(buffer);
                break;
            case GEOGRAPHY:
                GeographyValue gv = (GeographyValue)value;
                buffer.putInt(gv.getLengthInBytes());
                gv.flattenToBuffer(buffer);
                break;
            default:
                throw new RuntimeException("Cannot write to VoltDB UDF buffer.");
            }
        }

        public void call(ByteBuffer udfBuffer) throws Throwable {
            udfBuffer.clear();
            Object[] paramsIn = new Object[m_paramCount];
            for (int i = 0; i < m_paramCount; i++) {
                paramsIn[i] = getValueFromBuffer(udfBuffer, m_paramTypes[i]);
                if (m_boxUpByteArray[i]) {
                    paramsIn[i] = SerializationHelper.boxUpByteArray((byte[])paramsIn[i]);
                }
            }
            try {
                Object returnValue = m_functionMethod.invoke(m_functionInstance, paramsIn);
                udfBuffer.clear();
                writeValueToBuffer(udfBuffer, m_returnType, returnValue);
            }
            catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        }
    }
}
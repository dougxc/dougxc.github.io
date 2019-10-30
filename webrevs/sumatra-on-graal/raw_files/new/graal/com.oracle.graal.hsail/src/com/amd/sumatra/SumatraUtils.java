package com.amd.sumatra;

import java.lang.reflect.Field;

public class SumatraUtils {
    public static Object getFieldFromObject(Field f, Object fromObj) {
        try {
            f.setAccessible(true);
            java.lang.reflect.Type type = f.getType();

            if (type == double.class) {
                return f.getDouble(fromObj);
            } else if (type == float.class) {
                return f.getFloat(fromObj);
            } else if (type == long.class) {
                return f.getLong(fromObj);
            } else if (type == int.class) {
                return f.getInt(fromObj);
            } else if (type == byte.class) {
                return f.getByte(fromObj);
            } else if (type == boolean.class) {
                return f.getBoolean(fromObj);
            } else {
                // object
                return f.get(fromObj);
            }
        } catch (Exception e) {
            // fail("unable to get field " + f + " from " + fromObj);
            return null;
        }

    }

}
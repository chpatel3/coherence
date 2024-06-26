/*
 * Copyright (c) 2000, 2024, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */

package com.tangosol.io.pof;


import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


/**
* {@link PofSerializer} implementation that can be used to serialize all enum
* values.
*
* @author as  2008.10.24
*/
public class EnumPofSerializer<E extends Enum<E>>
        implements PofSerializer<E>
    {
    // ---- PofSerializer implementation ------------------------------------

    /**
    * {@inheritDoc}
    */
    public void serialize(PofWriter writer, E e)
            throws IOException
        {
        // COH-9833 getClass().isEnum() may return false for certain enums
        if (!(e instanceof Enum))
            {
            throw new IllegalArgumentException(
                    "EnumPofSerializer can only be used to serialize enum types.");
            }

        writer.writeString(0, e.name());
        writer.writeRemainder(null);
        }

    /**
    * {@inheritDoc}
    */
    public E deserialize(PofReader reader)
            throws IOException
        {
        PofContext ctx = reader.getPofContext();
        Class<E>   clz = ctx.getClass(reader.getUserTypeId());

        if (!clz.isEnum())
            {
            throw new IllegalArgumentException(
                    "EnumPofSerializer can only be used to deserialize enum types.");
            }

        E            enumValue  = null;
        List<String> enumValues = new ArrayList<String>();

        for (Object obj : clz.getEnumConstants())
            {
            enumValues.add(((Enum) obj).name());
            }

        String val = reader.readString(0);

        if (enumValues.contains(val))
            {
            enumValue = Enum.valueOf(clz, val);
            reader.registerIdentity(enumValue);
            reader.readRemainder();
            }

        reader.readRemainder();

        return enumValue;
        }
    }

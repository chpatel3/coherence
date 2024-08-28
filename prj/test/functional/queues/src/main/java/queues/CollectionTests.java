/*
 * Copyright (c) 2000, 2024, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */

package queues;

import com.tangosol.io.Serializer;
import com.tangosol.net.NamedCache;
import com.tangosol.net.NamedCollection;
import com.tangosol.net.Session;
import org.junit.jupiter.params.provider.Arguments;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

@SuppressWarnings("rawtypes")
interface CollectionTests<NC extends NamedCollection, C extends Collection>
    {
    /**
     * Obtain the {@link Serializer} names to use for
     * parameterized test {@link Arguments}.
     *
     * @return the {@link Serializer} names to use
     * for test {@link Arguments}
     */
    static Stream<Arguments> serializers()
        {
        List<Arguments> list = new ArrayList<>();
        list.add(Arguments.of("java"));
        list.add(Arguments.of("pof"));
        return list.stream();
        }

    /**
     * Return the {@link Session} to use to create queues.
     *
     * @return  the {@link Session} to use to create queues
     */
    Session getSession();

    default NC getNamedCollection(String sName)
        {
        return getNamedCollection(getSession(), sName);
        }

    NC getNamedCollection(Session session, String sName);

    C getCollection(Session session, String sName);

    NamedCache getCollectionCache(NC col);

    NamedCache getCollectionCache(String sName);
    
    C getNewCollection();

    C getNewCollection(String sPrefix);

    NC getNewNamedCollection();

    NC getNewNamedCollection(String sPrefix);

    C getCurrentCollection();

    C getCurrentCollection(String sPrefix);

    NC getCurrentNamedCollection();

    NC getCurrentNamedCollection(String sPrefix);

    String getNewName();

    String getNewName(String sPrefix);

    String getCurrentName();

    String getCurrentName(String sPrefix);
    }

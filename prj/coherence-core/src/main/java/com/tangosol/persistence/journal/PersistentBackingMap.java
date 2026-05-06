/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.persistence.journal;

/**
 * Marker for backing maps that provide persistence directly and therefore must
 * not also use the storage persistence side-channel.
 *
 * @author Aleks Seovic  2026.04.02
 * @since 26.04
 */
public interface PersistentBackingMap
    {
    }

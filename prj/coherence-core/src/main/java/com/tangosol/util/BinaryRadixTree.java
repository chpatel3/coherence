/*
 * Copyright (c) 2000, 2026, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.tangosol.util;


import com.oracle.coherence.common.base.Predicate;

import com.tangosol.coherence.config.Config;

import com.tangosol.io.AbstractReadBuffer;
import com.tangosol.io.WriteBuffer;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;


/**
 * A BinaryRadixTree is a memory-optimized radix tree (aka a Patricia trie)
 * that is intended to efficiently store a mapping from Binary values to long
 * values. The BinaryRadixTree is not thread safe; to make it safe in a
 * multi-threaded scenario, access must be synchronized and the Iterators
 * returned from the BinaryRadixTree can only be used within the scope of
 * synchronization within which they were obtained.
 *
 * @author cp 2010-06-06
 * @since Coherence 3.7
 */
public class BinaryRadixTree
        implements BinaryLongMap
    {
    // ----- constructors ----------------------------------------------------

    /**
     * Construct an empty BinaryRadixTree.
     */
    public BinaryRadixTree()
        {
        clear();
        }


    // ----- public interface ------------------------------------------------

    /**
     * {@inheritDoc}
     */
    public long get(Binary binKey)
        {
        return m_nodeRoot.findValue(binKey);
        }

    /**
     * {@inheritDoc}
     */
    public void put(Binary binKey, long lValue)
        {
        if (lValue == 0L)
            {
            remove(binKey);
            }
        else if (m_nodeRoot.modifyValue(binKey, 0, 0L, lValue, true, NO_INTDECO))
            {
            ++m_cKeys;
            }
        assert m_nodeRoot.check();
        }

    /**
     * {@inheritDoc}
     */
    public boolean putIfAbsent(Binary binKey, long lValue)
        {
        boolean fAdded = lValue != 0L && m_nodeRoot.modifyValue(binKey, 0, 0L, lValue, false, NO_INTDECO);
        if (fAdded)
            {
            ++m_cKeys;
            }
        assert m_nodeRoot.check();
        return fAdded;
        }

    /**
     * {@inheritDoc}
     */
    public boolean replace(Binary binKey, long lValueOld, long lValueNew)
        {
        if (lValueOld == 0L)
            {
            return putIfAbsent(binKey, lValueNew);
            }
        else if (lValueNew == 0L)
            {
            return remove(binKey, lValueOld);
            }
        else
            {
            boolean fReplaced = m_nodeRoot.modifyValue(binKey, 0, lValueOld, lValueNew, false, NO_INTDECO);
            assert m_nodeRoot.check();
            return fReplaced;
            }
        }

    /**
     * {@inheritDoc}
     */
    public void remove(Binary binKey)
        {
        if (m_nodeRoot.removeValue(binKey, 0, 0L, true))
            {
            --m_cKeys;
            }
        assert m_nodeRoot.check();
        }

    /**
     * {@inheritDoc}
     */
    public boolean remove(Binary binKey, long lValue)
        {
        boolean fRemoved = lValue != 0L && m_nodeRoot.removeValue(binKey, 0, lValue, false);
        if (fRemoved)
            {
            --m_cKeys;
            }
        assert m_nodeRoot.check();
        return fRemoved;
        }

    /**
     * {@inheritDoc}
     */
    public void clear()
        {
        m_nodeRoot = new SimpleParentValueNode(null, Binary.NO_BINARY, 0L, NO_INTDECO);
        m_cKeys    = 0;
        }

    /**
     * {@inheritDoc}
     */
    public int size()
        {
        return m_cKeys;
        }

    /**
     * {@inheritDoc}
     */
    public Iterator<Binary> keys()
        {
        int cSize = size();
        if (cSize == 0)
            {
            return Collections.<Binary>emptySet().iterator();
            }
        else
            {
            final List listKeys = new ArrayList(cSize);
            visitAll(new EntryVisitor()
                {
                public void visit(Entry entry)
                    {
                    listKeys.add(entry.getKey());
                    }
                });

            return listKeys.iterator();
            }
        }

    /**
     * {@inheritDoc}
     */
    public Iterator<Binary> keys(final Predicate<Entry> predicate)
        {
        int cSize = size();
        if (cSize == 0)
            {
            return Collections.<Binary>emptySet().iterator();
            }
        else
            {
            final List listKeys = new ArrayList(cSize / 8);
            visitAll(new EntryVisitor()
                {
                public void visit(Entry entry)
                    {
                    if (predicate.evaluate(entry))
                        {
                        listKeys.add(entry.getKey());
                        }
                    }
                });

            return listKeys.iterator();
            }
        }

    /**
     * {@inheritDoc}
     */
    public void visit(final Binary binKey, EntryVisitor visitor)
        {
        Node node = m_nodeRoot.findNode(binKey, 0);
        if (node == null)
            {
            visitor.visit(new Entry()
                {
                public Binary getKey()
                    {
                    return binKey;
                    }

                public long getValue()
                    {
                    // go through the front door in case this is called after setValue
                    return BinaryRadixTree.this.get(binKey);
                    }

                public Entry setValue(long lValue)
                    {
                    BinaryRadixTree.this.put(binKey, lValue);
                    return this;
                    }
                });
            }
        else
            {
            visitor.visit(node);
            }
        }

    /**
     * {@inheritDoc}
     */
    public void visitAll(EntryVisitor visitor)
        {
        if (size() > 0)
            {
            m_nodeRoot.visitAll(visitor);
            }
        }

    /**
     * {@inheritDoc}
     * <p>
     * To reduce memory footprint, the tree supports de-duplication of byte[]
     * values used internally by the tree. To execute the de-duping process,
     * create an empty <tt>byte[][]</tt> of a prime size, and pass it to the
     * <tt>internKeys()</tt> method of each available BinaryRadixTree.
     * <p>
     * Do not pre-populate the <tt>byte[][]</tt> or modify any of its contents.
     * <p>
     * Note: This method does not require external synchronization.
     *
     * @param o  an opaque <tt>byte[][]</tt> used to accumulate "intern()"
     *           byte[] values
     */
    public void internKeys(Object o)
        {
        if (o instanceof byte[][])
            {
            m_nodeRoot.dedupe((byte[][]) o);
            assert m_nodeRoot.check();
            }
        }

    /**
     * Return the number of bytes retained by this BinaryLongMap.
     *
     * @return the number of bytes retained by this BinaryLongMap
     */
    protected long sizeof()
        {
        return m_nodeRoot.sizeof() + 8;
        }


    // ----- private constants -----------------------------------------------

    /**
     * Constant used to indicate the absence of an int decoration.
     *
     * Note: Binary keys are not permitted use this value for the int decoration.
     */
    private static final int NO_INTDECO = Integer.MIN_VALUE;

    /**
     * Constant used to indicate whether each mutating operation should check
     * the validity of the tree.
     */
    private static final boolean VALIDATE = Config.getBoolean("coherence.brt.validate");

    // ----- data members ----------------------------------------------------

    /**
     * The root node of the tree; may be null if the tree is empty.
     */
    private Node m_nodeRoot;

    /**
     * A count of the Binary keys.
     */
    private int m_cKeys;


    // ----- inner class: Node -----------------------------------------------

    /**
     * The Node class represents a Binary Radix Tree Node holding an optional
     * long value. The various sub-classes optimize the memory size utilized by
     * the Node by varying the encoding of the binary sequence represented by
     * the Node, by optimizing storage of child references, and by optimizing
     * the storage of the value. In the case of the binary sequence encoding,
     * the bytes are either encoded into a long, or stored as a byte array. To
     * optimize child storage in the case of leaf nodes, various "Leaf Node"
     * implementations omit all overhead associated with managing children,
     * while the various "Parent Node" implementations use a dynamic approach:
     * <ul>
     * <li>Zero children are represented by a null reference to a child array;
     * <li>Up to 128 children are stored in a Node[4-128] (using a power-of-two
     *     size to eliminate repeated resizes), and ordered by their first byte
     *     so that a binary search can be used;
     * <li>Once the number of children pass 128, they are stored in a Node[256]
     *     array indexed by the first byte of the child's binary sequence
     * </ul>
     * <p>
     * The hierarchy is as follows:
     * <pre>
     * Node
     *   ParentNode (supports children)
     *     SimpleParentNode (adds support for byte[])
     *       SimpleParentValueNode (adds value)
     *     CompactParentNode (encodes bytes in long)
     *       CompactParentValueNode (adds value)
     *   LeafNode (always has value, never has children)
     *     SimpleLeafNode (adds support for byte[])
     *     CompactLeafNode (encodes bytes in long)
     * </pre>
     */
    private abstract static class Node
            extends AbstractByteSequence
            implements BinaryRadixTree.Entry
        {
        // ----- constructors --------------------------------------------

        /**
         * Construct a radix tree Node.
         *
         * @param nodeParent  the parent Node
         */
        protected Node(Node nodeParent)
            {
            m_nodeParent = nodeParent;
            }

        // ----- tree methods --------------------------------------------

        /**
         * Given the passed key, determine if this Node or a child Node matches
         * that key, and if so then return the associated value.
         *
         * @param binKey  the key to match against this Node
         *
         * @return the associated value, or 0L if no match is found
         */
        public long findValue(Binary binKey)
            {
            Node node = findNode(binKey, 0);
            return node == null ? 0L : node.getValue();
            }

        /**
         * Given the passed key, determine if this Node or a child Node matches
         * that key, and if so then return the associated Node.
         *
         * @param binKey  the key to match against this Node
         * @param ofKey   the offset within the key to match against this Node
         *
         * @return the associated Node, or null if no match is found
         */
        public Node findNode(Binary binKey, int ofKey)
            {
            // BRT holds the binary key decoration separately to increase density
            // thus skip the decoration if present
            int nDeco = NO_INTDECO;
            if (ofKey == 0)
                {
                int nDecoId = AbstractReadBuffer.readUnsignedByte(binKey, 0);
                if (nDecoId == ExternalizableHelper.FMT_IDO)
                    {
                    nDeco = AbstractReadBuffer.readPackedInt(binKey, 1);
                    ofKey = 1 + AbstractReadBuffer.sizeofPackedInt(nDeco);
                    }
                }

            int cbThis = this.length();
            int cbKey  = binKey.length() - ofKey;
            assert cbKey >= 0;

            if (cbThis > cbKey || (cbKey > cbThis && !this.hasChildren()))
                {
                // the key can not match
                return null;
                }

            // determine how much in common this node has with the remainder
            // of the passed key; either there is a difference within this
            // node, this node is the remainder of the key, or the difference
            // is within one of the child nodes (whether or not they actually
            // exist)
            for (int of = 0; of < cbThis; ++of)
                {
                if (binKey.byteAt(ofKey + of) != this.byteAt(of))
                    {
                    return null;
                    }
                }

            // matched this node in its entirety; if there is nothing left of
            // the key to match, then this is the node that was searched for
            if (cbThis == cbKey)
                {
                return this;
                }

            // otherwise the search continues on a child node
            int  ofNext    = ofKey + cbThis;
            Node nodeChild = getChild(binKey.byteAt(ofNext));
            Node nodeMatch = nodeChild == null ? null : nodeChild.findNode(binKey, ofNext);

            assert nDeco == NO_INTDECO || nodeMatch == null || nDeco == nodeMatch.getKeyDecoration();

            return nodeMatch;
            }

        /**
         * Associate the passed value with the specified key, optionally
         * performing the operation only if the value currently associated with
         * the key matches the passed "old" value.
         *
         * @param binKey     the key
         * @param ofKey      the offset within the key corresponding to the
         *                   first byte of the binary sequence represented by
         *                   this node
         * @param lValueOld  the value that is assumed to be currently
         *                   associated with the key
         * @param lValueNew  the value to associate with the key
         * @param fForce     true to ignore the old value
         * @param nDeco      an integer decoration for this key
         *
         * @return if <tt>fForce</tt> is true, then the return value is true
         *         iff an item was added as the result of this operation; if
         *         <tt>fForce</tt> is false, then the return value is true iff
         *         the operation affected a change to the radix tree
         */
        public boolean modifyValue(ByteSequence binKey, int ofKey, long lValueOld,
                                   long lValueNew, boolean fForce, int nDeco)
            {
            // BRT stores the binary key decoration separately to increase
            // density; extract the decoration to be held on the value node
            if (ofKey == 0)
                {
                assert nDeco == NO_INTDECO;

                int nDecoId = AbstractReadBuffer.readUnsignedByte(binKey, 0);
                if (nDecoId == ExternalizableHelper.FMT_IDO)
                    {
                    nDeco = AbstractReadBuffer.readPackedInt(binKey, 1);
                    ofKey = 1 + AbstractReadBuffer.sizeofPackedInt(nDeco);

                    assert nDeco != NO_INTDECO;
                    }
                }

            int cbThis      = this.length();
            int cbKeyTotal  = binKey.length();
            int cbKeyRemain = cbKeyTotal - ofKey;
            assert cbKeyRemain >= 0;

            // match as much as possible from the key and from this node
            int of = 0;
            int cb = Math.min(cbThis, cbKeyRemain);
            while (of < cb)
                {
                if (binKey.byteAt(ofKey + of) != this.byteAt(of))
                    {
                    // no match; have to split this node
                    if (fForce || lValueOld == 0L)
                        {
                        Node that = this.split(of, 0L, NO_INTDECO);
                        that.addChild(LeafNode.instantiateLeaf(that,
                                binKey.subSequence(ofKey + of, cbKeyTotal), lValueNew, nDeco));
                        return true;
                        }
                    else
                        {
                        // no change (the old value didn't match)
                        return false;
                        }
                    }

                ++of;
                }

            // if it was a complete match, then replace this node's value
            if (cbKeyRemain == cbThis)
                {
                // two threads can try to update the same key concurrently
                synchronized (this)
                    {
                    long lValuePrev = getValue();
                    if (fForce || lValueOld == lValuePrev)
                        {
                        setValue(lValueNew).setKeyDecoration(nDeco);
                        return !fForce || lValuePrev == 0L;
                        }
                    else
                        {
                        // no change (the old value didn't match)
                        return false;
                        }
                    }
                }
            // if there is anything left to this node, then it needs to be split
            else if (cbThis > cbKeyRemain)
                {
                // the node we're looking for ends in the middle of this node,
                // so this node needs to be split)
                if (fForce || lValueOld == 0L)
                    {
                    split(cbKeyRemain, lValueNew, nDeco);
                    return true;
                    }
                else
                    {
                    // no change (the old value didn't match)
                    return false;
                    }
                }
            else // if (cbKeyRemain > cbThis)
                {
                int  ofNext    = ofKey + cbThis;
                Node nodeChild = getChild(binKey.byteAt(ofNext));
                if (nodeChild == null)
                    {
                    if (fForce || lValueOld == 0L)
                        {
                        addChild(LeafNode.instantiateLeaf(this,
                                binKey.subSequence(ofNext, cbKeyTotal), lValueNew, nDeco));
                        return true;
                        }
                    else
                        {
                        return false;
                        }
                    }
                else
                    {
                    return nodeChild.modifyValue(binKey, ofNext, lValueOld, lValueNew, fForce, nDeco);
                    }
                }
            }

        /**
         * Remove the specified key from the radix tree, optionally performing
         * the operation only if the value currently associated with the key
         * matches the passed "old" value.
         *
         * @param binKey  the key
         * @param ofKey   the offset within the key corresponding to the first
         *                byte of the binary sequence represented by this node
         * @param lValue  the value that is assumed to be currently associated
         *                with the key
         * @param fForce  true to ignore the old value
         *
         * @return true iff the operation affected a change to the radix tree
         */
        public boolean removeValue(Binary binKey, int ofKey, long lValue, boolean fForce)
            {
            // BRT stores the binary key decoration separately to increase
            // density; skip the decoration on the provided key
            if (ofKey == 0)
                {
                int nDecoId = AbstractReadBuffer.readUnsignedByte(binKey, 0);
                if (nDecoId == ExternalizableHelper.FMT_IDO)
                    {
                    int nDeco = AbstractReadBuffer.readPackedInt(binKey, 1);
                    ofKey = 1 + AbstractReadBuffer.sizeofPackedInt(nDeco);
                    }
                }

            int cbThis      = this.length();
            int cbKeyRemain = binKey.length() - ofKey;
            assert cbKeyRemain >= 0;

            if (cbThis > cbKeyRemain || (cbKeyRemain > cbThis && !this.hasChildren()))
                {
                // the key can not match
                return false;
                }

            // determine how much in common this node has with the remainder
            // of the passed key; either there is a difference within this
            // node, this node is the remainder of the key, or the difference
            // is within one of the child nodes (whether or not they actually
            // exist)
            for (int of = 0; of < cbThis; ++of)
                {
                if (binKey.byteAt(ofKey + of) != this.byteAt(of))
                    {
                    return false;
                    }
                }

            // matched this node in its entirety; if there is nothing left of
            // the key to match, then this is the node that was searched for
            if (cbThis == cbKeyRemain)
                {
                if (fForce || lValue == getValue())
                    {
                    // remove this entry (if it has children, then just zero
                    // it out)
                    if (hasChildren())
                        {
                        if (getValue() != 0L)
                            {
                            // getKeyDecoration() == nDeco;
                            setValue(0L).setKeyDecoration(NO_INTDECO);
                            }

                        if (getChildCount() == 1)
                            {
                            // we can merge the only remaining child into this
                            merge(iterateChildren().next());
                            }
                        }
                    else
                        {
                        // getKeyDecoration() == nDeco;
                        getParent().removeChild(this);
                        }
                    return true;
                    }
                else
                    {
                    // value didn't match, so don't remove
                    return false;
                    }
                }

            // otherwise the search continues on a child node
            int  ofNext    = ofKey + cbThis;
            Node nodeChild = getChild(binKey.byteAt(ofNext));
            if (nodeChild != null &&
                nodeChild.removeValue(binKey, ofNext, lValue, fForce))
                {
                // reevaluate whether this node is needed
                if (!hasChildren() && getValue() == 0L)
                    {
                    Node nodeParent = getParent();
                    if (nodeParent != null)
                        {
                        nodeParent.removeChild(this);
                        }
                    }

                return true;
                }
            return false;
            }

        // ----- parent --------------------------------------------------

        /**
         * Determine the parent Node for this radix tree Node.
         *
         * @return the parent Node, or null iff this is the root Node
         */
        public Node getParent()
            {
            return m_nodeParent;
            }

        /**
         * If the parent node is replaced, its children are updated via this
         * method.
         *
         * @param nodeOldParent  the old parent reference
         * @param nodeNewParent  the new parent reference
         */
        public void replaceParent(Node nodeOldParent, Node nodeNewParent)
            {
            assert getParent() == nodeOldParent;
            m_nodeParent = nodeNewParent;
            }

        // ----- key -----------------------------------------------------

        /**
         * {@inheritDoc}
         */
        public Binary getKey()
            {
            return populateBinaryWriteBuffer(0, NO_INTDECO).getBuffer().toBinary();
            }

        // ----- binary sequence -----------------------------------------

        /**
         * Determine the first byte represented by this Node. In other words,
         * what byte differentiates this Node from its siblings.
         * <p>
         * This is equivalent to {@link #byteAt(int) byteAt(0)} and as such can
         * throw an IndexOutOfBoundsException; as a result, it is safe to call
         * this on all child Nodes, but not on the root Node.
         *
         * @return the first byte represented by this Node
         */
        public byte getFirstByte()
            {
            return byteAt(0);
            }

        /**
         * {@inheritDoc}
         * <p>
         * The byte length represents how many bytes are common to all children
         * of this Node (assuming the node has children). All nodes have a byte
         * length of <tt>(n >= 1)</tt> except for the root Node, which may have
         * a byte length of 0, since there may be no common "prefix" across all
         * Binary entries.
         */
        public abstract int length();

        /**
         * {@inheritDoc}
         */
        public abstract byte byteAt(int of);

        /**
         * {@inheritDoc}
         */
        public Binary toBinary()
            {
            int cb = length();
            WriteBuffer.BufferOutput out = new BinaryWriteBuffer(cb, cb).getBufferOutput();
            try
                {
                appendBytesTo(out);
                }
            catch (IOException e)
                {
                throw Base.ensureRuntimeException(e);
                }

            return out.getBuffer().toBinary();
            }


        // ----- binary sequence de-duping -------------------------------

        /**
         * Attempt to "intern()" all byte arrays used by this Node and any
         * nodes under it.
         *
         * @param aab  an array of intern()'d byte arrays
         */
        public void dedupe(byte[][] aab)
            {
            byte[] ab = getByteArray();
            if (ab != null)
                {
                int    nHash  = (int) ((Base.toCrc(ab) & 0xFFFFFFFFL) % aab.length);
                byte[] abPrev = aab[nHash];
                if (abPrev != ab)
                    {
                    if (abPrev == null)
                        {
                        // use our byte[] as the intern() for those bytes
                        aab[nHash] = ab;
                        }
                    else
                        {
                        int cb     = ab.length;
                        int cbPrev = abPrev.length;
                        if (cb == cbPrev && Binary.equals(ab, 0, abPrev, 0, cb))
                            {
                            // same byte[] contents, so use the same byte[]
                            // reference
                            setByteArray(abPrev);
                            }
                        }
                    }
                }

            if (hasChildren())
                {
                for (Iterator<Node> iter = iterateChildren(); iter.hasNext(); )
                    {
                    iter.next().dedupe(aab);
                    }
                }
            }

        /**
         * Obtain the byte[] used by the Node.
         *
         * @return the byte[] containing this Node's ByteSequence, or null
         */
        protected byte[] getByteArray()
            {
            return null;
            }

        /**
         * Replace the byte[] used by the Node. This method is only valid if
         * the Node implementation uses a byte[] to store its ByteSequence.
         *
         * @param ab  the byte[] to use for this Node's ByteSequence
         */
        protected void setByteArray(byte[] ab)
            {
            throw new UnsupportedOperationException();
            }

        // ----- value ---------------------------------------------------

        /**
         * Obtain the value stored with the Node if any.
         * <p>
         * Note that it is not possible to differentiate between a zero value
         * and a Node that has no value at all.
         *
         * @return the long value or zero if there is no value
         */
        public long getValue()
            {
            return 0L;
            }

        /**
         * Modify the value stored in this Node.
         * <p>
         * Note that this operation may cause this Node to be replaced with
         * another Node instance.
         *
         * @param lValue  the new value for this Node
         *
         * @return this Node or the Node that replaces it if the result of the
         *         operation replaces this Node
         */
        public abstract Node setValue(long lValue);

        // ----- hash code of binary key ---------------------------------

        /**
         * Return an integer decoration for this key.
         * <p>
         * Note: this implementation is only appropriate for value nodes.
         *
         * @return an integer decoration for this key
         */
        public int getKeyDecoration()
            {
            return 0;
            }

        /**
         * Set an integer decoration for this key.
         * <p>
         * Note: this implementation is only appropriate for value nodes.
         *
         * @param nDeco  an integer decoration for this key
         */
        public void setKeyDecoration(int nDeco)
            {
            }

        // ----- children ------------------------------------------------

        /**
         * Determine if the node has any children.
         *
         * @return true iff the node has any children
         */
        public boolean hasChildren()
            {
            return getChildCount() > 0;
            }

        /**
         * Determine how many children the Node has.
         *
         * @return the number of child Nodes
         */
        public abstract int getChildCount();

        /**
         * Obtain the child (if any) whose ByteSequence begins with the
         * specified byte.
         *
         * @param b  the byte that the child's ByteSequence starts with
         *
         * @return the child Node if there is a child Node whose ByteSequence
         *         begins with the specified byte; otherwise null
         */
        public abstract Node getChild(byte b);

        /**
         * Determine the child nodes of this radix tree node (if any).
         * <p>
         * Note that the returned Iterator is not guaranteed to be either
         * thread-safe or safe for use if any subsequent mutating operations
         * occur against this Node.
         *
         * @return an Iterator of child nodes
         */
        public abstract Iterator<Node> iterateChildren();

        /**
         * Apply the specified visitor to this node (if this node exists) and
         * all children of this node.
         *
         * @param visitor  the visitor to apply
         */
        public void visitAll(EntryVisitor visitor)
            {
            visitor.visit(this);
            }

        /**
         * Add a child Node.
         * <p>
         * Note that this operation may cause this Node to be replaced with
         * another Node instance.
         *
         * @param node  the child Node to add
         *
         * @return this Node or the Node that replaces it if the result of the
         *         operation replaces this Node
         */
        public abstract Node addChild(Node node);

        /**
         * Replace a child Node with another Node for the same first byte.
         * <p>
         * Note that this operation may cause this Node to be replaced with
         * another Node instance.
         *
         * @param nodeOld  the reference to the child that is being replaced
         * @param nodeNew  the reference to the new child Node
         *
         * @return this Node or the Node that replaces it if the result of the
         *         operation replaces this Node
         */
        public abstract Node replaceChild(Node nodeOld, Node nodeNew);

        /**
         * Remove a child Node.
         * <p>
         * Note that this operation may cause this Node to be replaced with
         * another Node instance.
         *
         * @param node  the child Node to replace
         *
         * @return this Node or the Node that replaces it if the result of the
         *         operation replaces this Node, or null
         */
        public abstract Node removeChild(Node node);

        // ----- misc ----------------------------------------------------

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString()
            {
            StringBuilder sb = new StringBuilder();
            sb.append(ClassHelper.getSimpleName(getClass()))
              .append("{")
              .append(super.toString());

            sb.append(", ChildCount=")
              .append(getChildCount())
              .append(", Value=")
              .append(getValue())
              .append("}");

            return sb.toString();
            }

        /**
         * Print the contents of this Node and all Nodes under it recursively.
         */
        public void print()
            {
            printWithIndent(Base.getOut(), "");
            }

        /**
         * Using the specified PrintWriter and indentation string, print the
         * contents of this Node and all Nodes under it recursively.
         *
         * @param out      a PrintWriter
         * @param sIndent  an indentation string for the current Node
         */
        public void printWithIndent(PrintWriter out, String sIndent)
            {
            // print this node's information
            out.println(sIndent + toString());

            // print children with a further indent
            sIndent += "  ";
            for (Iterator<Node> iter = iterateChildren(); iter.hasNext(); )
                {
                iter.next().printWithIndent(out, sIndent);
                }
            }

        /**
         * Obtain a formatting debug string of the Node and its sub-nodes
         * (recursively).
         *
         * @return the output from {@link #print()}
         */
        public String toDebugString()
            {
            StringWriter writer = new StringWriter();
            PrintWriter  out    = new PrintWriter(writer, true);
            printWithIndent(out, "* ");
            return writer.toString();
            }

        /**
         * Conduct various debugging checks.
         *
         * @return true if everything checks out OK
         */
        public boolean check()
            {
            if (!VALIDATE)
                {
                return true;
                }

            boolean fSuccess = true;
            try
                {
                for (Iterator<Node> iter = iterateChildren(); iter.hasNext(); )
                    {
                    Node child = iter.next();
                    if (child.getParent() != this)
                        {
                        Base.out("parent/child incorrect:");
                        printWithIndent(Base.getOut(), "* ");
                        fSuccess = false;
                        }

                    fSuccess &= child.check();
                    }
                }
            catch (Exception e)
                {
                Base.out(e);
                fSuccess = false;
                }

            return fSuccess;
            }

        /**
         * Return the number of bytes consumed by this Node and all children.
         *
         * @return the number of bytes consumed by this Node and all children
         */
        protected abstract long sizeof();

        // ----- helpers -------------------------------------------------

        /**
         * Split this Node into two Nodes at the specified offset.
         *
         * @param ofSplit  the offset within this Node to split off a new Node
         * @param lValue   the value for the "prefix" Node of the split
         * @param nDeco    an integer decoration for this key
         *
         * @return the Node that represents the binary sequence of this Node
         *         from offset zero to the split point
         */
        protected Node split(int ofSplit, long lValue, int nDeco)
            {
            Node nodeParent = getParent();
            Node that = ParentNode.instantiateParent(nodeParent,
                    new PartialByteSequence(this, 0, ofSplit), lValue, nDeco);

            ByteSequence seqChild    = new PartialByteSequence(this, ofSplit, length() - ofSplit);
            long         lChildValue = getValue();
            Node         nodeChild;
            if (this.hasChildren())
                {
                nodeChild = ParentNode.instantiateParent(that, seqChild, lChildValue, getKeyDecoration());

                // move children
                for (Iterator<Node> iter = iterateChildren(); iter.hasNext(); )
                    {
                    nodeChild.addChild(iter.next());
                    }
                }
            else
                {
                nodeChild = LeafNode.instantiateLeaf(that, seqChild, lChildValue, getKeyDecoration());
                }
            that.addChild(nodeChild);

            // notify parent
            if (nodeParent != null)
                {
                nodeParent.replaceChild(this, that);
                }

            return that;
            }

        /**
         * Merge this Node and its one remaining child.
         *
         * @param nodeChild  the child node
         *
         * @return the Node that represents the merged binary sequence of this
         *         Node and its one remaining child
         */
        protected Node merge(Node nodeChild)
            {
            assert nodeChild != null;
            assert getChild(nodeChild.getFirstByte()) == nodeChild;
            assert nodeChild.getParent() == this;
            assert getChildCount() == 1;
            assert getValue() == 0L;

            Node         that;
            Node         nodeParent = getParent();
            ByteSequence seqThat    = new AggregateByteSequence(this, nodeChild);
            long         lValue     = nodeChild.getValue();
            int          nDeco      = nodeChild.getKeyDecoration();
            if (nodeChild.hasChildren())
                {
                that = ParentNode.instantiateParent(nodeParent, seqThat, lValue, nDeco);

                // move children
                for (Iterator<Node> iter = nodeChild.iterateChildren(); iter.hasNext(); )
                    {
                    that.addChild(iter.next());
                    }
                }
            else
                {
                that = LeafNode.instantiateLeaf(nodeParent, seqThat, lValue, nDeco);
                }

            // notify parent
            if (nodeParent != null)
                {
                nodeParent.replaceChild(this, that);
                }

            return that;
            }

        /**
         * Do the necessary work to replace this node with another node.
         *
         * @param that  the replacement node
         *
         * @return the replacement node
         */
        protected Node replaceSelf(Node that)
            {
            // notify all children
            for (Iterator<Node> iter = that.iterateChildren(); iter.hasNext(); )
                {
                iter.next().replaceParent(this, that);
                }

            // notify parent
            Node nodeParent = getParent();
            if (nodeParent != null)
                {
                nodeParent.replaceChild(this, that);
                }

            return that;
            }

        /**
         * Obtain a BufferOutput that contains this Node's binary sequence
         * appended to its parent's sequence and (recursively) so on.
         *
         * @param cbDescendants  the aggregate number of bytes in the binary
         *                       sequences of each Node in the chain of
         *                       descendants whose recursive execution has
         *                       culminated in the present invocation
         * @param nDeco          the decoration to write at the head of the
         *                       WriteBuffer
         *
         * @return the BufferOutput that contains the binary sequence of this
         *         Node and all its ancestors
         */
        protected WriteBuffer.BufferOutput populateBinaryWriteBuffer(final int cbDescendants, int nDeco)
            {
            int cbTotal = cbDescendants + length();

            if (cbDescendants == 0) // value node
                {
                nDeco = getKeyDecoration();
                }

            Node                     parent = getParent();
            WriteBuffer.BufferOutput out;
            if (parent == null)
                {
                cbTotal += 6; // allocate additional space for decoration
                out      = new BinaryWriteBuffer(cbTotal, cbTotal).getBufferOutput();
                if (nDeco != NO_INTDECO)
                    {
                    try
                        {
                        out.writeByte(ExternalizableHelper.FMT_IDO);
                        out.writePackedInt(nDeco);
                        }
                    catch (IOException e)
                        {
                        throw Base.ensureRuntimeException(e);
                        }
                    }
                }
            else
                {
                out = parent.populateBinaryWriteBuffer(cbTotal, nDeco);
                }

            try
                {
                appendBytesTo(out);
                }
            catch (IOException e)
                {
                throw Base.ensureRuntimeException(e);
                }

            return out;
            }

        /**
         * Write this node's binary sequence to the passed BufferOutput.
         *
         * @param out  a non-null BufferOutput to write to
         *
         * @throws IOException  theoretically from writing to the BufferOutput
         */
        protected abstract void appendBytesTo(WriteBuffer.BufferOutput out)
                throws IOException;

        // ----- data members --------------------------------------------

        /**
         * Reference to the node that is the parent of this node, or null iff
         * this is the root node of the radix tree.
         */
        private Node m_nodeParent;
        }


    // ----- inner class: ParentNode -----------------------------------------

    /**
     * A ParentNode is one that is capable of containing child Nodes.
     */
    private abstract static class ParentNode
            extends Node
        {
        /**
         * Construct a ParentNode.
         *
         * @param nodeParent  the parent Node of this ParentNode
         */
        protected ParentNode(Node nodeParent)
            {
            super(nodeParent);
            }

        /**
         * Copy constructor for a ParentNode.
         *
         * @param nodeParent  the parent Node of this ParentNode
         * @param that        the ParentNode to (shallow) copy from
         */
        protected ParentNode(Node nodeParent, ParentNode that)
            {
            this(nodeParent);
            this.m_anodeChildren = that.m_anodeChildren;
            }

        /**
         * Factory: Create a parent Node based on the passed parameters.
         *
         * @param nodeParent  the parent of the new Node
         * @param seq         the ByteSequence for the new Node
         * @param lValue      an optional value
         * @param nDeco       an integer decoration for this key
         *
         * @return a new Parent node
         */
        public static ParentNode instantiateParent(Node nodeParent, ByteSequence seq, long lValue, int nDeco)
            {
            if (seq.length() <= 6)
                {
                return lValue == 0L
                        ? new CompactParentNode(nodeParent, seq)
                        : new CompactParentValueNode(nodeParent, seq, lValue, nDeco);
                }
            else
                {
                return lValue == 0L
                        ? new SimpleParentNode(nodeParent, seq)
                        : new SimpleParentValueNode(nodeParent, seq, lValue, nDeco);
                }
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public abstract int getChildCount();

        /**
         * Store the number of child Nodes.
         *
         * @param cChildren  the number of child Nodes
         */
        protected abstract void setChildCount(int cChildren);

        /**
         * {@inheritDoc}
         */
        @Override
        public Node getChild(byte b)
            {
            Node[] anode  = m_anodeChildren;
            int    cNodes = anode.length;
            if (cNodes == 0x100)
                {
                return anode[b & 0xFF];
                }
            else
                {
                int iNode = findChildIndex(b);
                return iNode < 0 ? null : anode[iNode];
                }
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public Iterator<Node> iterateChildren()
            {
            Node[] anode = m_anodeChildren;
            return anode.length == 0x100
                   ? new FilterEnumerator(anode, NullFilter.getInstance())
                   : new SimpleEnumerator(anode, 0, getChildCount());
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public void visitAll(EntryVisitor visitor)
            {
            if (getValue() != 0L)
                {
                super.visitAll(visitor);
                }

            Node[] anode = m_anodeChildren;
            if (anode.length == 0x100)
                {
                // just skip the nulls
                for (int i = 0; i < 0x100; ++i)
                    {
                    Node node = anode[i];
                    if (node != null)
                        {
                        node.visitAll(visitor);
                        }
                    }
                }
            else
                {
                for (int i = 0, c = getChildCount(); i < c; ++i)
                    {
                    anode[i].visitAll(visitor);

                    // if the node was removed by visiting it, the remainder
                    // of the child array (the unvisited children) will have
                    // been "compacted" by sliding them to the left by one
                    // slot
                    if (getChildCount() < c)
                        {
                        assert getChildCount() == c - 1;
                        --i;
                        --c;
                        }
                    }
                }
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public Node addChild(Node node)
            {
            int     bNode     = node.getFirstByte() & 0xFF;
            Node[]  anodeSrc  = m_anodeChildren;             // children array
            Node[]  anodeDest = anodeSrc;
            int     cSrc      = anodeSrc.length;             // array size
            int     cDest     = cSrc;
            int     cNodes    = getChildCount();             // child count
            boolean fGrow     = cNodes >= cSrc;

            if (fGrow)
                {
                // grow the array (maintain power of two size, max 0x100)
                assert cSrc < 0x100;
                cDest = cSrc * 2;
                m_anodeChildren = anodeDest = new Node[cDest];

                if (cDest == 0x100)
                    {
                    // when the array size is maxed out, the nodes are indexed
                    // by their first byte
                    for (int iElement = 0; iElement < cSrc; ++iElement)
                        {
                        Node nodeCopy  = anodeSrc[iElement];
                        int  bNodeCopy = nodeCopy.getFirstByte() & 0xFF;
                        assert anodeDest[bNodeCopy] == null;
                        anodeDest[bNodeCopy] = nodeCopy;
                        }
                    }
                }

            if (cDest == 0x100)
                {
                assert anodeDest[bNode] == null;
                anodeDest[bNode] = node;
                }
            else
                {
                int iIns = -1 - binarySearch(anodeSrc, cNodes, bNode);
                assert iIns >= 0;

                if (fGrow && iIns > 0)
                    {
                    // copy all elements from the source to the destination up
                    // to the point of insertion
                    System.arraycopy(anodeSrc, 0, anodeDest, 0, iIns);
                    }

                if (iIns < cNodes)
                    {
                    // if the array is growing, then this copies all of the
                    // elements from the source to the destination that come
                    // after the point of insertion; otherwise it simply
                    // shifts all of the elements that occur at and after the
                    // point of insertion to the "right" by one
                    System.arraycopy(anodeSrc, iIns, anodeDest, iIns + 1, cNodes - iIns);
                    }

                anodeDest[iIns] = node;
                }

            Node nodeOldParent = node.getParent();
            if (nodeOldParent != this)
                {
                node.replaceParent(nodeOldParent, this);
                }

            setChildCount(cNodes + 1);
            return this;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public Node replaceChild(Node nodeOld, Node nodeNew)
            {
            byte b = nodeOld.getFirstByte();
            assert b == nodeNew.getFirstByte();

            int iNode = findChildIndex(b);
            assert iNode >= 0;

            Node[] anode = m_anodeChildren;
            assert anode[iNode] == nodeOld;

            anode[iNode] = nodeNew;
            return this;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public Node removeChild(Node node)
            {
            byte b     = node.getFirstByte();
            int  iNode = findChildIndex(b);
            assert iNode >= 0;

            Node[] anode = m_anodeChildren;
            assert anode[iNode] == node;

            int cElements = anode.length;
            if (cElements == 0x100)
                {
                anode[iNode] = null;
                }
            else
                {
                // delete the node from the ordered array
                int iLast = cElements - 1;
                if (iNode < iLast)
                    {
                    System.arraycopy(anode, iNode + 1, anode, iNode, iLast - iNode);
                    }
                anode[iLast] = null;
                }

            setChildCount(getChildCount() - 1);
            return this;
            }

        /**
         * Search for the specified child.
         *
         * @param b  the first byte of the byte sequence of the child that is
         *           being searched for
         *
         * @return the child index in the children array, or -1 if not found
         */
        protected int findChildIndex(byte b)
            {
            Node[] anode  = m_anodeChildren;
            int    cNodes = anode.length;
            int    bNode  = b & 0xFF;
            return cNodes == 0x100
                    ? (anode[bNode] == null ? -1 : bNode)
                    : Math.max(-1, binarySearch(anode, getChildCount(), bNode));
            }

        /**
         * Using the binary search algorithm, searches the specified array of
         * nodes for the node starting with the specified byte.
         *
         * @param anode   the array of Node objects to be searched
         * @param cNodes  the number of Node objects in the array
         * @param bNode   the byte value to be searched for
         *
         * @return index of the Node starting with the specified byte, if it
         *         is contained in the array; otherwise, <tt>(-(<i>insertion
         *         point</i>) - 1)</tt>. The <i>insertion point</i> is defined
         *         as the point at which the key would be inserted into the
         *         array: the index of the first element greater than the key,
         *         or <tt>anode.length</tt> if all elements in the array are
         *         less than the specified key. Note that this guarantees that
         *         the return value will be &gt;= 0 if and only if the key is
         *         found.
         */
        private static int binarySearch(Node[] anode, int cNodes, int bNode)
            {
            int iLow  = 0;
            int iHigh = cNodes - 1;
            while (iLow <= iHigh)
                {
                // pick a Node to act as the root of the tree (or
                // sub-tree) that is being searched
                int iRoot = (iLow + iHigh) >>> 1;

                // first byte of the byte sequence of that Node
                Node nodeRoot = anode[iRoot];
                int  bRoot    = nodeRoot.getFirstByte() & 0xFF;

                if (bNode < bRoot)
                    {
                    // go "left" in the binary tree
                    iHigh = iRoot - 1;
                    }
                else if (bNode > bRoot)
                    {
                    // go "right" in the binary tree
                    // this is the new insertion point
                    iLow = iRoot + 1;
                    }
                else
                    {
                    return iRoot;
                    }
                }

            // node was not found; return an insertion point
            return -(iLow + 1);
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean check()
            {
            if (!VALIDATE)
                {
                return true;
                }

            boolean fSuccess = true;
            try
                {
                Node[] anode     = m_anodeChildren;
                int    cChildren = 0;
                Iterator<Node> iter = iterateChildren();
                for (int i = 0, c = anode.length, bPrev = -1; i < c; ++i)
                    {
                    Node node = anode[i];
                    if (node != null)
                        {
                        ++cChildren;

                        if (node != iter.next())
                            {
                            Base.out("iterator mismatch: " + toDebugString());
                            fSuccess = false;
                            }

                        int bChild = node.getFirstByte() & 0xFF;
                        if (bChild <= bPrev)
                            {
                            Base.out("child starting with byte " + bChild
                                    + " out of order: " + toDebugString());
                            fSuccess = false;
                            }

                        bPrev = bChild;
                        }
                    }

                // iterator must also be exhausted
                if (iter.hasNext())
                    {
                    Base.out("iterator mismatch: " + toDebugString());
                    fSuccess = false;
                    }

                if (cChildren != getChildCount())
                    {
                    Base.out("count mismatch: actual=" + cChildren
                            + ", ChildCount=" + getChildCount());
                    fSuccess = false;
                    }

                fSuccess &= super.check();
                }
            catch (Exception e)
                {
                Base.out(e);
                fSuccess = false;
                }

            return fSuccess;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        protected long sizeof()
            {
            int cb = 0;
            for (Iterator iter = iterateChildren(); iter.hasNext(); )
                {
                cb += 4 + ((Node) iter.next()).sizeof();
                }
            return cb;
            }

        /**
         * An array of child Nodes. The array size will be between 4 and 256,
         * and the size will be a power of two. If the array size is between 4
         * and 128, the child Nodes are stored in the array ordered by the
         * first byte of their binary sequence (i.e. for binary search); if the
         * array size is 256, then the child Nodes are stored in the array
         * indexed by the first byte of their binary sequence (i.e. for instant
         * access).
         */
        private Node[] m_anodeChildren = new Node[4];
        }


    // ----- inner class: SimpleParentNode -----------------------------------

    /**
     * A SimpleParentNode is a ParentNode that stores its ByteSequence in a
     * byte array.
     */
    private static class SimpleParentNode
            extends ParentNode
        {
        /**
         * Construct a SimpleParentNode.
         *
         * @param nodeParent  the parent Node for this Node
         * @param seq         the byte sequence for this Node
         */
        public SimpleParentNode(Node nodeParent, ByteSequence seq)
            {
            super(nodeParent);

            int    cb = seq.length();
            byte[] ab = new byte[cb];
            for (int of = 0; of < cb; ++of)
                {
                ab[of] = seq.byteAt(of);
                }
            m_ab = ab;
            }

        /**
         * Copy-construct a SimpleParentNode.
         *
         * @param that  the SimpleParentNode to copy from
         */
        protected SimpleParentNode(SimpleParentNode that)
            {
            super(that.getParent());
            this.m_ab        = that.m_ab;
            this.m_cChildren = that.m_cChildren;
            }

        /**
         * {@inheritDoc}
         */
        @Override public int length()
            {
            return m_ab.length;
            }

        /**
         * {@inheritDoc}
         */
        @Override public byte byteAt(int of)
            {
            return m_ab[of];
            }

        /**
         * {@inheritDoc}
         */
        @Override protected long sizeof()
            {
            return super.sizeof() + length() + 2;
            }

        /**
         * {@inheritDoc}
         */
        @Override protected byte[] getByteArray()
            {
            return m_ab;
            }

        /**
         * {@inheritDoc}
         */
        @Override protected void setByteArray(byte[] ab)
            {
            m_ab = ab;
            }

        /**
         * {@inheritDoc}
         */
        @Override public Node setValue(long lValue)
            {
            return replaceSelf(new SimpleParentValueNode(this, lValue, NO_INTDECO));
            }

        /**
         * {@inheritDoc}
         */
        @Override public int getChildCount()
            {
            return m_cChildren;
            }

        /**
         * {@inheritDoc}
         */
        @Override protected void setChildCount(int cChildren)
            {
            m_cChildren = (short) cChildren;
            }

        /**
         * {@inheritDoc}
         */
        @Override protected void appendBytesTo(WriteBuffer.BufferOutput out)
                throws IOException
            {
            out.write(m_ab);
            }

        /**
         * The bytes that this Node represents.
         */
        private byte[] m_ab;

        /**
         * The number of children (0-256 inclusive).
         */
        private short m_cChildren;
        }


    // ----- inner class: SimpleParentValueNode ------------------------------

    /**
     * The SimpleParentValueNode implementation adds value storage to the
     * SimpleParentNode implementation.
     * <p>
     * Note that this implementation doesn't attempt to optimize itself in the
     * case that the value is set back to zero; i.e. it doesn't attempt to
     * replace itself with a non-value-holding instance.
     */
    private static class SimpleParentValueNode
            extends SimpleParentNode
        {
        /**
         * Construct a SimpleParentValueNode.
         *
         * @param nodeParent  the parent Node for this Node
         * @param seq         the byte sequence for this Node
         * @param lValue      the value to associate with this Node
         * @param nDeco       an integer decoration for this key
         */
        public SimpleParentValueNode(Node nodeParent, ByteSequence seq, long lValue, int nDeco)
            {
            super(nodeParent, seq);
            m_lValue = lValue;
            m_nDeco  = nDeco;
            }

        /**
         * Constructor for SimpleParentNode to create a SimpleParentValueNode
         * to replace itself with.
         *
         * @param that    the SimpleParentNode to copy to create this
         * @param lValue  the value to associate with this Node
         * @param nDeco   an integer decoration for this key
         */
        public SimpleParentValueNode(SimpleParentNode that, long lValue, int nDeco)
            {
            super(that);
            m_lValue = lValue;
            m_nDeco  = nDeco;
            }

        /**
         * {@inheritDoc}
         */
        @Override public long getValue()
            {
            return m_lValue;
            }

        /**
         * {@inheritDoc}
         */
        @Override public Node setValue(long lValue)
            {
            m_lValue = lValue;
            return this;
            }

        /**
         * {@inheritDoc}
         */
        @Override public int getKeyDecoration()
            {
            return m_nDeco;
            }

        /**
         * {@inheritDoc}
         */
        @Override public void setKeyDecoration(int nDeco)
            {
            m_nDeco = nDeco;
            }

        /**
         * {@inheritDoc}
         */
        @Override protected long sizeof()
            {
            return super.sizeof() + 12;
            }

        /**
         * The value for this Node.
         */
        private long m_lValue;

        /**
         * The integer decoration for this key.
         */
        private int m_nDeco;
        }


    // ----- inner class: CompactParentNode ----------------------------------

    /**
     * A CompactLeafNode is a LeafNode that stores its ByteSequence in a
     * compact manner.
     */
    private static class CompactParentNode
            extends ParentNode
        {
        /**
         * Construct a CompactParentNode.
         *
         * @param nodeParent  the parent Node for this Node
         * @param seq         the byte sequence for this Node
         */
        public CompactParentNode(Node nodeParent, ByteSequence seq)
            {
            super(nodeParent);

            int cb = seq.length();
            assert cb <= 6;

            long lBytes = ((long) cb) << 60;
            for (int of = 0; of < cb; ++of)
                {
                lBytes |= ((long) (seq.byteAt(of) & 0xFF)) << (of << 3);
                }
            m_lBytes = lBytes;
            }

        /**
         * Copy-construct a CompactParentNode.
         *
         * @param that  the CompactParentNode to copy from
         */
        protected CompactParentNode(CompactParentNode that)
            {
            super(that.getParent(), that);
            this.m_lBytes = that.m_lBytes;
            }

        /**
         * {@inheritDoc}
         */
        @Override public byte getFirstByte()
            {
            return (byte) (((int) m_lBytes) & 0xFF);
            }

        /**
         * {@inheritDoc}
         */
        @Override public int length()
            {
            return (int) (m_lBytes >>> 60);
            }

        /**
         * {@inheritDoc}
         */
        @Override public byte byteAt(int of)
            {
            long lBytes = m_lBytes;
            int  cb     = length();
            if (of >= cb)
                {
                throw new IndexOutOfBoundsException("of=" + of + ", cb=" + cb);
                }
            return (byte) (((int) (lBytes >>> (of << 3))) & 0xFF);
            }

        /**
         * {@inheritDoc}
         */
        @Override public Node setValue(long lValue)
            {
            return replaceSelf(new CompactParentValueNode(this, lValue, NO_INTDECO));
            }

        /**
         * {@inheritDoc}
         */
        @Override public int getChildCount()
            {
            return ((int) (m_lBytes >>> 48)) & 0xFFF;
            }

        /**
         * {@inheritDoc}
         */
        @Override protected long sizeof()
            {
            return super.sizeof() + 8;
            }

        /**
         * {@inheritDoc}
         */
        @Override protected void setChildCount(int cChildren)
            {
            m_lBytes = ((long) (cChildren & 0xFFF)) << 48 | (m_lBytes & 0xF000FFFFFFFFFFFFL);
            }

        /**
         * {@inheritDoc}
         */
        @Override protected void appendBytesTo(WriteBuffer.BufferOutput out)
                throws IOException
            {
            int  cb     = length();
            long lBytes = m_lBytes;
            for (int of = 0; of < cb; ++of)
                {
                out.write((int) lBytes);
                lBytes >>>= 8;
                }
            }

        /**
         * The compact form of the Node stores its bytes encoded in a long,
         * with the first nibble of the long encoding the length (0-6) of the
         * ByteSequence, and the ByteSequence itself stored in bytes 2-7 of the
         * long in reverse sequence. The second nibble of byte 0 and the
         * entirety of byte 1 are used to encode the child count. (Byte numbers
         * are expressed assuming a long is composed of bytes 01234567.)
         */
        private long m_lBytes;
        }


    // ----- inner class: CompactParentValueNode -----------------------------

    /**
     * The CompactParentValueNode implementation adds value storage to the
     * CompactParentNode implementation.
     * <p>
     * Note that this implementation doesn't attempt to optimize itself in the
     * case that the value is set back to zero; i.e. it doesn't attempt to
     * replace itself with a non-value-holding instance.
     */
    private static class CompactParentValueNode
            extends CompactParentNode
        {
        /**
         * Construct a CompactParentValueNode.
         *
         * @param nodeParent  the parent Node for this Node
         * @param seq         the byte sequence for this Node
         * @param lValue      the value to associate with this Node
         * @param nDeco       an integer decoration for this key
         */
        public CompactParentValueNode(Node nodeParent, ByteSequence seq, long lValue, int nDeco)
            {
            super(nodeParent, seq);
            m_lValue = lValue;
            m_nDeco  = nDeco;
            }

        /**
         * Constructor for CompactParentNode to create a CompactParentValueNode
         * to replace itself with.
         *
         * @param that    the CompactParentNode to copy to create this
         * @param lValue  the value to associate with this Node
         * @param nDeco   an integer decoration for this key
         */
        public CompactParentValueNode(CompactParentNode that, long lValue, int nDeco)
            {
            super(that);
            m_lValue = lValue;
            m_nDeco  = nDeco;
            }

        /**
         * {@inheritDoc}
         */
        @Override public long getValue()
            {
            return m_lValue;
            }

        /**
         * {@inheritDoc}
         */
        @Override public Node setValue(long lValue)
            {
            m_lValue = lValue;
            return this;
            }

        /**
         * {@inheritDoc}
         */
        @Override public int getKeyDecoration()
            {
            return m_nDeco;
            }

        /**
         * {@inheritDoc}
         */
        @Override public void setKeyDecoration(int nDeco)
            {
            m_nDeco = nDeco;
            }

        /**
         * {@inheritDoc}
         */
        @Override protected long sizeof()
            {
            return super.sizeof() + 12;
            }

        /**
         * The value for this Node.
         */
        private long m_lValue;

        /**
         * The integer decoration for this key.
         */
        private int m_nDeco;
        }


    // ----- inner class: LeafNode -------------------------------------------

    /**
     * A LeafNode is a Node that has no children and almost certainly has a
     * value.
     */
    private abstract static class LeafNode
            extends Node
        {
        /**
         * Construct a LeafNode.
         *
         * @param nodeParent  the parent Node for this Node
         * @param lValue      the value for this Node
         * @param nDeco       an integer decoration for this key
         */
        protected LeafNode(Node nodeParent, long lValue, int nDeco)
            {
            super(nodeParent);
            m_lValue = lValue;
            m_nDeco  = nDeco;
            }

        /**
         * Factory: Create a leaf Node based on the passed parameters.
         *
         * @param nodeParent  the parent of the new Node
         * @param seq         the ByteSequence for the new Node
         * @param lValue      an optional value
         * @param nDeco       an integer decoration for this key
         *
         * @return a new leaf Node
         */
        public static LeafNode instantiateLeaf(Node nodeParent, ByteSequence seq, long lValue, int nDeco)
            {
            return seq.length() <= 7
                    ? new CompactLeafNode(nodeParent, seq, lValue, nDeco)
                    : new SimpleLeafNode(nodeParent, seq, lValue, nDeco);
            }

        // ----- key -----------------------------------------------------

        /**
         * {@inheritDoc}
         */
        public Binary getKey()
            {
            Binary binKey = m_refKey == null ? null : m_refKey.get();

            if (binKey == null)
                {
                binKey   = super.getKey();
                m_refKey = new SoftReference<>(binKey);
                }

            return binKey;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public long getValue()
            {
            return m_lValue;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public Node setValue(long lValue)
            {
            m_lValue = lValue;
            return this;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public int getKeyDecoration()
            {
            return m_nDeco;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public void setKeyDecoration(int nDeco)
            {
            m_nDeco = nDeco;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean hasChildren()
            {
            return false;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public int getChildCount()
            {
            return 0;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public Node getChild(byte b)
            {
            return null;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public Iterator<Node> iterateChildren()
            {
            return Collections.<Node>emptySet().iterator();
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public Node addChild(Node node)
            {
            Node that = ParentNode.instantiateParent(getParent(), this, getValue(), getKeyDecoration());
            replaceSelf(that);
            that.addChild(node);
            return that;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public Node replaceChild(Node nodeOld, Node nodeNew)
            {
            throw new IllegalArgumentException("child=" + nodeOld);
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public Node removeChild(Node node)
            {
            throw new IllegalArgumentException("child=" + node);
            }

        /**
         * {@inheritDoc}
         */
        @Override
        protected long sizeof()
            {
            return 12;
            }

        /**
         * The value for this Node.
         */
        private long m_lValue;

        /**
         * The integer decoration for this key.
         */
        private int m_nDeco;

        /**
         * Soft reference to key to prevent expensive re-materialization of key
         * through tree traversal.
         */
        private SoftReference<Binary> m_refKey;
        }


    // ----- inner class: SimpleLeafNode -------------------------------------

    /**
     * A SimpleLeafNode is a LeafNode that stores its ByteSequence in a byte
     * array.
     */
    private static class SimpleLeafNode
            extends LeafNode
        {
        /**
         * Construct a SimpleLeafNode.
         *
         * @param nodeParent  the parent Node for this Node
         * @param seq         the byte sequence for this Node
         * @param lValue      the value for this Node
         * @param nDeco       an integer decoration for this key
         */
        public SimpleLeafNode(Node nodeParent, ByteSequence seq, long lValue, int nDeco)
            {
            super(nodeParent, lValue, nDeco);

            int    cb = seq.length();
            byte[] ab = new byte[cb];
            for (int of = 0; of < cb; ++of)
                {
                ab[of] = seq.byteAt(of);
                }
            m_ab = ab;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public int length()
            {
            return m_ab.length;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public byte byteAt(int of)
            {
            return m_ab[of];
            }

        /**
         * {@inheritDoc}
         */
        @Override
        protected long sizeof()
            {
            return super.sizeof() + length();
            }

        /**
         * {@inheritDoc}
         */
        @Override
        protected byte[] getByteArray()
            {
            return m_ab;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void setByteArray(byte[] ab)
            {
            m_ab = ab;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void appendBytesTo(WriteBuffer.BufferOutput out)
                throws IOException
            {
            out.write(m_ab);
            }

        /**
         * The bytes that this Node represents.
         */
        private byte[] m_ab;
        }


    // ----- inner class: CompactLeafNode ------------------------------------

    /**
     * A CompactLeafNode is a LeafNode that stores its ByteSequence in a
     * compact manner.
     */
    private static class CompactLeafNode
            extends LeafNode
        {
        /**
         * Construct a CompactLeafNode.
         *
         * @param nodeParent  the parent Node for this Node
         * @param seq         the byte sequence for this Node
         * @param lValue      the value for this Node
         * @param nDeco       an integer decoration for this key
         */
        public CompactLeafNode(Node nodeParent, ByteSequence seq, long lValue, int nDeco)
            {
            super(nodeParent, lValue, nDeco);

            int cb = seq.length();
            assert cb <= 7;

            long lBytes = ((long) cb) << 56;
            for (int of = 0; of < cb; ++of)
                {
                lBytes |= ((long) (seq.byteAt(of) & 0xFF)) << (of << 3);
                }
            m_lBytes = lBytes;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public byte getFirstByte()
            {
            return (byte) (((int) m_lBytes) & 0xFF);
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public int length()
            {
            return (int) (m_lBytes >>> 56);
            }

        /**
         * {@inheritDoc}
         */
        @Override
        public byte byteAt(int of)
            {
            long lBytes = m_lBytes;
            int  cb     = (int) (lBytes >>> 56);
            if (of >= cb)
                {
                throw new IndexOutOfBoundsException("of=" + of + ", cb=" + cb);
                }
            return (byte) (((int) (lBytes >>> (of << 3))) & 0xFF);
            }

        /**
         * {@inheritDoc}
         */
        @Override
        protected long sizeof()
            {
            return super.sizeof() + 8;
            }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void appendBytesTo(WriteBuffer.BufferOutput out)
                throws IOException
            {
            int  cb     = length();
            long lBytes = m_lBytes;
            for (int of = 0; of < cb; ++of)
                {
                out.write((int) lBytes);
                lBytes >>>= 8;
                }
            }

        /**
         * The compact form of the Node stores its bytes encoded in a long,
         * with the first byte of the long encoding the length (1-7) of the
         * ByteSequence, and the ByteSequence itself stored in bytes 1-7 of the
         * long in reverse sequence.
         */
        private long m_lBytes;
        }
    }

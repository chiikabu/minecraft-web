// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.system;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public abstract class Struct<T extends Struct<T>> {
    protected long address;
    protected ByteBuffer container;

    public Struct() {}

    protected Struct(long address, ByteBuffer container) {
        if (address == 0) throw new NullPointerException();
        this.address = address;
        this.container = container;
    }

    public long address() { return address; }

    protected static Member __member(int size) { return __member(size, size); }
    protected static Member __member(int size, int alignment) { return new Member(size, alignment); }

    protected static Layout __struct(Member... members) {
        List<Member> flattened = new ArrayList<>();
        int size = 0, alignment = 0;
        for (Member member : members) {
            member.offset = align(size, member.getAlignment());
            size = member.offset + member.size;
            alignment = Math.max(alignment, member.getAlignment());
            flattened.add(member);
            if (member instanceof Layout nested) {
                for (Member child : nested.members) {
                    child.offset += member.offset;
                    flattened.add(child);
                }
            }
        }
        return new Layout(align(size, alignment), alignment, flattened.toArray(new Member[0]));
    }

    private static int align(int size, int alignment) { return ((size - 1) | (alignment - 1)) + 1; }

    public static class Member {
        final int size;
        final int alignment;
        int offset;

        Member(int size, int alignment) {
            this.size = size;
            this.alignment = alignment;
        }

        public int getSize() { return size; }
        public int getAlignment() { return alignment; }
    }

    public static class Layout extends Member {
        final Member[] members;

        Layout(int size, int alignment, Member[] members) {
            super(size, alignment);
            this.members = members;
        }

        public int offsetof(int index) { return members[index].offset; }
    }
}

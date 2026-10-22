// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/** Guard the coordinator's actual bytecode, independent of timing noise or work counters. */
public final class PlanningHotMethodTest {
    public static void main(String[] args) throws Exception {
        // A preview branch pushed step from 7801 to 8067 bytes. On the supported
        // HotSpot 17 defaults it then stayed interpreted, making identical work
        // much slower. Keep cold phases in helpers before reaching that limit.
        int largest = 0;
        try (var in = new DataInputStream(GraphPlanningWork.class.getResourceAsStream("GraphPlanningWork.class"))) {
            check(in.readInt() == 0xcafebabe, "Not a class file");
            in.readUnsignedShort(); in.readUnsignedShort();
            String[] pool = new String[in.readUnsignedShort()];
            for (int i = 1; i < pool.length; i++) {
                switch (in.readUnsignedByte()) {
                    case 1 -> pool[i] = in.readUTF();
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                    case 5, 6 -> { in.skipNBytes(8); i++; }
                    case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
                    case 15 -> in.skipNBytes(3);
                    default -> throw new AssertionError("Unknown constant pool tag");
                }
            }
            in.skipNBytes(6);
            in.skipNBytes(2L * in.readUnsignedShort());
            for (int fields = in.readUnsignedShort(); fields > 0; fields--) {
                in.skipNBytes(6);
                skipAttributes(in);
            }
            for (int methods = in.readUnsignedShort(); methods > 0; methods--) {
                in.readUnsignedShort();
                String name = pool[in.readUnsignedShort()];
                in.readUnsignedShort();
                for (int attributes = in.readUnsignedShort(); attributes > 0; attributes--) {
                    String attribute = pool[in.readUnsignedShort()];
                    int length = in.readInt();
                    if (attribute.equals("Code") && (name.equals("step") || name.equals("advance"))) {
                        try (var code = new DataInputStream(new ByteArrayInputStream(in.readNBytes(length)))) {
                            code.readUnsignedShort(); code.readUnsignedShort();
                            int bytes = code.readInt(); largest = Math.max(largest, bytes);
                            check(bytes <= 8000, name + " exceeds default hot-method compilation limit: " + bytes);
                        }
                    } else in.skipNBytes(Integer.toUnsignedLong(length));
                }
            }
        }
        check(largest > 0, "No coordinator method inspected");
        System.out.println("PlanningHotMethodTest PASS: largest coordinator method=" + largest + " bytes");
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        for (int count = in.readUnsignedShort(); count > 0; count--) {
            in.readUnsignedShort(); in.skipNBytes(Integer.toUnsignedLong(in.readInt()));
        }
    }
    private static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
}

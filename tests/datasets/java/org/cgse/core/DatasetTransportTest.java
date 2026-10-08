package org.cgse.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Guards lossless slot/catalyst transport and absent-versus-explicit-empty producer semantics. */
public final class DatasetTransportTest {
    private static void text(DataOutputStream output, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static DatasetReplay.Catalog fixture(int producers) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(1); text(output, "seed"); output.writeLong(Long.MAX_VALUE);
            output.writeInt(1); text(output, "external");
            output.writeBoolean(false); output.writeBoolean(true);
            output.writeInt(7); output.writeLong(64);
            output.writeInt(1); text(output, "recipe"); text(output, "provider-binding");
            output.writeInt(2);
            text(output, "seed"); output.writeLong(3); output.writeInt(8);
            output.writeBoolean(true); output.writeBoolean(true);
            text(output, "seed"); output.writeLong(5); output.writeInt(2);
            output.writeBoolean(false); output.writeBoolean(false);
            output.writeInt(2); text(output, "product"); output.writeLong(2);
            text(output, "seed"); output.writeLong(3);
            output.writeInt(producers);
            if (producers > 0) { text(output, "product"); output.writeInt(1); text(output, "recipe"); }
        }
        return DatasetReplay.catalog(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    }

    public static void main(String[] args) throws Exception {
        var data = fixture(1);
        if (data.stock().get("seed") != Long.MAX_VALUE || !data.external().contains("external")
                || data.preserve() || !data.force() || data.parallelism() != 7 || data.extraCopies() != 64)
            throw new AssertionError("Inventory or catalyst settings changed");
        var recipe = data.recipes().get(0);
        if (!recipe.binding().equals("provider-binding") || !recipe.slots().equals(List.of(
                new GraphRecipe.Slot<>("seed", 3, 8, true, true),
                new GraphRecipe.Slot<>("seed", 5, 2, false, false))))
            throw new AssertionError("Slots were merged, reordered, or lost configuration/reusable flags");
        if (!data.producers().get("product").equals(List.of(recipe))) throw new AssertionError("Producer order lost");
        if (!fixture(0).producers().isEmpty()) throw new AssertionError("Explicit empty producers must stay empty");
        if (fixture(-1).producers().get("product").size() != 1) throw new AssertionError("Absent producers must be derived");
        System.out.println("DatasetTransportTest PASS");
    }
}

package local.recipedump;

import appeng.api.stacks.*;
import com.google.gson.*;
import com.gregtechceu.gtceu.api.recipe.*;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.*;
import com.gregtechceu.gtceu.api.capability.recipe.*;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.common.extensions.IForgeItemStack;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.gtlcore.gtlcore.common.item.VirtualIngredientBehavior;
import net.minecraft.nbt.TagParser;
import java.nio.file.*;
import java.util.*;

/** Local data collector only. No world, machine, inventory or recipe mutation. */
@net.minecraftforge.fml.common.Mod("localrecipedump")
public final class RecipeExport {
    static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    static String key(ItemStack stack) { return AEItemKey.of(stack).toTagGeneric().toString(); }
    static String key(FluidStack stack) { return AEFluidKey.of(stack.getFluid(), stack.getTag()).toTagGeneric().toString(); }
    static JsonObject item(ItemStack stack, long amount, boolean remainder) {
        JsonObject o = new JsonObject(); o.addProperty("key", key(stack)); o.addProperty("amount", Long.toString(amount));
        if (remainder) {
            ItemStack remaining = ((IForgeItemStack)(Object)stack).getCraftingRemainingItem();
            if (!remaining.m_41619_()) { o.addProperty("remaining_key", key(remaining)); o.addProperty("remaining_amount", remaining.m_41613_()); }
        }
        return o;
    }
    static JsonObject ingredient(Object value, boolean remainder) {
        JsonObject o = new JsonObject(); o.addProperty("class", value.getClass().getName()); JsonArray alternatives = new JsonArray();
        if (value instanceof Ingredient ingredient) {
            o.add("raw", ingredient.m_43942_());
            long amount = ingredient instanceof LongIngredient l ? l.getActualAmount() : ingredient instanceof SizedIngredient s ? s.getAmount() : 1;
            // A count provider is stochastic; never silently sample it into a deterministic recipe.
            Ingredient inner = ingredient instanceof SizedIngredient s ? s.getInner() : ingredient;
            if (inner instanceof IntProviderIngredient) o.addProperty("random_amount", true);
            Set<String> seen = new HashSet<>();
            for (ItemStack stack : ingredient.m_43908_()) if (!stack.m_41619_() && seen.add(key(stack))) alternatives.add(item(stack, amount, remainder));
        } else if (value instanceof ItemStack stack) {
            if (!stack.m_41619_()) alternatives.add(item(stack, stack.m_41613_(), remainder));
        } else if (value instanceof FluidIngredient ingredient) {
            o.add("raw", ingredient.toJson());
            Set<String> seen = new HashSet<>();
            for (FluidStack stack : ingredient.getStacks()) if (stack.getAmount() > 0 && seen.add(key(stack))) {
                JsonObject a = new JsonObject(); a.addProperty("key", key(stack)); a.addProperty("amount", Long.toString(ingredient.getAmount())); alternatives.add(a);
            }
        } else if (value instanceof FluidStack stack) {
            JsonObject a = new JsonObject(); a.addProperty("key", key(stack)); a.addProperty("amount", Long.toString(stack.getAmount())); alternatives.add(a);
        } else o.addProperty("unhandled", String.valueOf(value));
        o.add("alternatives", alternatives); return o;
    }
    static JsonArray contents(Map<RecipeCapability<?>, List<Content>> map) {
        JsonArray result = new JsonArray();
        for (var entry : map.entrySet()) for (Content c : entry.getValue()) {
            JsonObject o = ingredient(c.content, false);
            o.addProperty("capability", entry.getKey().name); o.addProperty("chance", c.chance); o.addProperty("max_chance", c.maxChance);
            if(c.chance==0)for(var value:o.getAsJsonArray("alternatives")) {
                var option=value.getAsJsonObject();
                try {
                    AEKey k=AEKey.fromTagGeneric(TagParser.m_129359_(option.get("key").getAsString()));
                    ItemStack virtual=k instanceof AEItemKey item?VirtualIngredientBehavior.wrap(item.toStack(1)):
                        k instanceof AEFluidKey fluid?VirtualIngredientBehavior.wrap(FluidStack.create(fluid.getFluid(),1)):null;
                    if(virtual!=null&&!virtual.m_41619_())option.addProperty("virtual_key",key(virtual));
                }catch(Exception ex){throw new IllegalStateException("Cannot encode actual AE virtual input",ex);}
            }
            o.addProperty("tier_chance_boost", c.tierChanceBoost); o.addProperty("slot", c.slotName); result.add(o);
        }
        return result;
    }
    public static String run(RecipeManager manager, RegistryAccess registries) throws Exception {
        Path dir = Path.of("local/recipe-export-virtual"); Files.createDirectories(dir);
        List<Recipe<?>> recipes = new ArrayList<>(manager.m_44051_()); recipes.sort(Comparator.comparing(r -> r.m_6423_().toString()));
        Map<String,Integer> types = new TreeMap<>(); int errors = 0, gt = 0;
        try (var writer = Files.newBufferedWriter(dir.resolve("recipes.jsonl"))) {
            for (Recipe<?> recipe : recipes) {
                JsonObject o = new JsonObject(); o.addProperty("id", recipe.m_6423_().toString()); o.addProperty("class", recipe.getClass().getName());
                o.addProperty("serializer", String.valueOf(ForgeRegistries.RECIPE_SERIALIZERS.getKey(recipe.m_7707_())));
                String type = recipe.m_6671_().toString(); o.addProperty("type", type); types.merge(type, 1, Integer::sum);
                try {
                    if (recipe instanceof GTRecipe r) {
                        gt++; o.addProperty("gt", true); o.add("inputs", contents(r.inputs)); o.add("outputs", contents(r.outputs));
                        o.add("tick_inputs", contents(r.tickInputs)); o.add("tick_outputs", contents(r.tickOutputs));
                        o.addProperty("duration", r.duration); o.addProperty("conditions", String.valueOf(r.conditions));
                        o.addProperty("ingredient_actions", String.valueOf(r.ingredientActions)); o.addProperty("data", r.data.toString());
                    } else {
                        JsonArray in = new JsonArray();
                        for (Ingredient ingredient : recipe.m_7527_()) if (!ingredient.m_43947_()) in.add(ingredient(ingredient, true));
                        o.add("inputs", in); JsonArray out = new JsonArray(); ItemStack result = recipe.m_8043_(registries);
                        if (!result.m_41619_()) out.add(item(result, result.m_41613_(), false)); o.add("outputs", out);
                        o.addProperty("custom", recipe instanceof CustomRecipe);
                    }
                } catch (Throwable error) { errors++; o.addProperty("error", error.toString()); }
                writer.write(JSON.toJson(o)); writer.newLine();
            }
        }
        JsonObject summary = new JsonObject(); summary.addProperty("total", recipes.size()); summary.addProperty("gt", gt); summary.addProperty("errors", errors);
        summary.add("types", JSON.toJsonTree(types)); Files.writeString(dir.resolve("summary.json"), JSON.toJson(summary));
        return JSON.toJson(summary);
    }
}

package org.cgse.core;
import java.math.*;import java.util.*;
public class PlaceOracle {
 public static void main(String[] args){var random=new Random(299253);int pools=0,guards=0,feasible=0;
 for(int sample=0;sample<2000;sample++){int keys=3+random.nextInt(4),n=2+random.nextInt(5);var recipes=new ArrayList<GraphRecipe<String>>();for(int r=0;r<n;r++){var inputs=new ArrayList<GraphRecipe.Slot<String>>();var output=new LinkedHashMap<String,Long>();output.put("Q",1L);for(int k=0;k<keys;k++){int in=random.nextInt(3),out=random.nextInt(3);if(in>0)inputs.add(new GraphRecipe.Slot<>("K"+k,in));if(out>0)output.put("K"+k,(long)out);}recipes.add(new GraphRecipe<>("r"+r,"r"+r,inputs,output));}var stock=new LinkedHashMap<String,Long>();for(int k=0;k<keys;k++)stock.put("K"+k,(long)random.nextInt(5));var b=ScheduleOracle.budget();var compiler=new GraphCompiler<>(recipes);try(var model=RecipeCountModel.create(compiler,"Q",1,stock,Map.of(),Set.of(),Set.of(),false,b)){
 for(var pool:CountPlaceSets.separate(model,b)){pools++;var p=new HashSet<>(pool);if(!CountPlaceSets.valid(model.recipes,p,false,b)&&!CountPlaceSets.valid(model.recipes,p,true,b))throw new AssertionError("not a place set");}
 try(var execution=new CountExecution<>(model,b)){execution.refine(model);for(int variant=0;variant<8;variant++){int[] total=new int[n];for(int i=0;i<n;i++)total[i]=random.nextInt(3);if(!ScheduleOracle.bfs(model.recipes,total,stock))continue;feasible++;var point=Arrays.stream(total).mapToObj(v->ExactRational.of(BigInteger.valueOf(v))).toArray(ExactRational[]::new);for(var proof:execution.proofs()){guards++;if(proof.guard().violated(point,b))throw new AssertionError("execution proof excluded an enabled interleaving");}}}}
 if(b.reservedBytes()!=0)throw new AssertionError("place memory leak");}
 if(pools==0||guards==0)throw new AssertionError("unexercised");System.out.println("PASS place set separation models=2000 pools="+pools+" checked execution guards="+guards+" feasible multiset schedules="+feasible);
 }
}

package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.math.BigInteger;
import java.util.*;
public final class ViewShapeProbe {
 static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
 static final List<Map<String,Object>> exported=new ArrayList<>();
 static void views(String id,List<ExactLinearProgram.Constraint>rows,BigInteger[]lo,BigInteger[]hi,List<String>names,PlanningBudget b){
  try(var views=CountModelViews.create(rows,lo,hi,b);var reduced=new CountReduction(rows,lo,hi,b)){
   if(views==null)throw new AssertionError("view denied");views.compileLight();while(!reduced.step()){}views.addReduced(reduced);
   for(var v:views.available()){
    Map<String,Object>x=new LinkedHashMap<>();x.put("id",id+"/"+v.name());x.put("lower",v.lower());x.put("upper",v.upper());x.put("rows",v.rows());x.put("variables",v.lower().length);x.put("terms",v.shape().terms());x.put("unfixed",v.shape().unfixed());
    List<String>fixed=new ArrayList<>();int amos=0,unary=0,tautology=0;
    for(int i=0;i<v.lower().length;i++)if(v.lower()[i].equals(v.upper()[i]))fixed.add((v.inverse()==null?names.get(i):"reduced"+i)+"="+v.lower()[i]);
    for(var row:v.rows()){
     if(row.terms().size()<=1)unary++;
     BigInteger capacity=row.upper(),sum=BigInteger.ZERO,min=null,max=BigInteger.ZERO;
     for(var t:row.terms().entrySet()){
      int i=t.getKey();BigInteger c=t.getValue();capacity=capacity.subtract(c.multiply(v.lower()[i]));if(v.lower()[i].equals(v.upper()[i]))continue;
      if(c.signum()<0)capacity=capacity.subtract(c);BigInteger w=c.abs();sum=sum.add(w);min=min==null?w:min.min(w);max=max.max(w);
     }
     if(sum.compareTo(capacity)<=0)tautology++;
     if(min!=null&&min.shiftLeft(1).compareTo(capacity)>0&&max.compareTo(capacity)<=0)amos++;
    }
    x.put("fixed",fixed);x.put("amoRows",amos);x.put("unaryRows",unary);x.put("tautologyRows",tautology);exported.add(x);
    System.out.println(id+"/"+v.name()+" variables="+v.lower().length+" rows="+v.rows().size()+" terms="+v.shape().terms()+" unfixed="+v.shape().unfixed()+" amo="+amos+" unary="+unary+" tautology="+tautology+" fixed="+fixed);
   }
  }
 }
 public static void main(String[]args)throws Exception{
  for(var element:JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray()){
   var c=element.getAsJsonObject();String id=c.get("id").getAsString();if(!id.contains("lseu-objective"))continue;
   int n=c.getAsJsonArray("lower").size();var lo=new BigInteger[n];var hi=new BigInteger[n];List<String>names=new ArrayList<>();for(int i=0;i<n;i++){lo[i]=c.getAsJsonArray("lower").get(i).getAsBigInteger();hi[i]=c.getAsJsonArray("upper").get(i).getAsBigInteger();names.add("var"+i);}
   List<ExactLinearProgram.Constraint>rows=new ArrayList<>();for(var e:c.getAsJsonArray("rows")){var row=e.getAsJsonObject();Map<Integer,BigInteger>terms=new LinkedHashMap<>();for(var t:row.getAsJsonObject("terms").entrySet())terms.put(Integer.parseInt(t.getKey()),t.getValue().getAsBigInteger());rows.add(new ExactLinearProgram.Constraint(terms,row.get("upper").getAsBigInteger()));}
   var b=new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);views(id+"/raw",rows,lo,hi,names,b);if(b.reservedBytes()!=0)throw new AssertionError("raw leak");
   var embedding=new CountBenchmark.Embedding(rows,lo,hi);var compiler=new GraphCompiler<>(embedding.recipes);
   try(var model=RecipeCountModel.create(compiler,"target",1,embedding.stock,Map.of(),Set.of(),Set.of(),true,b);var bounds=new CountBounds(model.recipes.size(),model.constraints,b,model.constraints.size())){
    while(!bounds.step()){}var all=new ArrayList<>(model.constraints);all.addAll(bounds.tightened());
    views(id+"/embedded",all,bounds.lowerBounds(),bounds.upperBounds(),model.recipes.stream().map(GraphRecipe::id).toList(),b);
   }
   if(b.reservedBytes()!=0)throw new AssertionError("embedded leak "+b.reservedBytes());
  }
  Files.writeString(Path.of(args[1]),JSON.toJson(exported));
 }
}

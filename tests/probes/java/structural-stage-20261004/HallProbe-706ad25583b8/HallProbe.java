package org.cgse.core;
import java.util.*;
import java.math.BigInteger;
import java.nio.file.*;
import com.google.gson.Gson;

public class HallProbe {
 static long checks,oldWork,newWork; static int models,cuts;
 static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] lo,BigInteger[] hi,List<List<Integer>> groups,int[] resource){}
 static Model model(int[][] graph,int resources,Random random){
  var groups=new ArrayList<List<Integer>>();var rows=new ArrayList<ExactLinearProgram.Constraint>();var res=new ArrayList<Integer>();
  for(int[] g:graph){var ids=new ArrayList<Integer>();var plus=new LinkedHashMap<Integer,BigInteger>();var minus=new LinkedHashMap<Integer,BigInteger>();for(int r:g){int id=res.size();ids.add(id);res.add(r);plus.put(id,BigInteger.ONE);minus.put(id,BigInteger.ONE.negate());}rows.add(new ExactLinearProgram.Constraint(plus,BigInteger.ONE));rows.add(new ExactLinearProgram.Constraint(minus,BigInteger.ONE.negate()));groups.add(ids);}
  for(int r=0;r<resources;r++){var terms=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<res.size();i++)if(res.get(i)==r)terms.put(i,BigInteger.ONE);rows.add(new ExactLinearProgram.Constraint(terms,BigInteger.ONE));}
  BigInteger[] lo=new BigInteger[res.size()],hi=new BigInteger[lo.length];Arrays.fill(lo,BigInteger.ZERO);Arrays.fill(hi,BigInteger.ONE);
  if(random!=null){Collections.shuffle(rows,random);for(int i=0;i<lo.length;i++){if(random.nextInt(12)==0)hi[i]=BigInteger.ZERO;else if(random.nextInt(20)==0)lo[i]=BigInteger.ONE;}}
  return new Model(rows,lo,hi,groups,res.stream().mapToInt(i->i).toArray());
 }
 static List<BigInteger[]> solutions(Model m){var out=new ArrayList<BigInteger[]>();BigInteger[] p=new BigInteger[m.lo.length];Arrays.fill(p,BigInteger.ZERO);enumerate(m,0,new BitSet(),p,out);return out;}
 static void enumerate(Model m,int g,BitSet used,BigInteger[] p,List<BigInteger[]> out){
  if(g==m.groups.size()){for(int i=0;i<p.length;i++)if(p[i].compareTo(m.lo[i])<0)return;out.add(p.clone());return;}
  for(int i:m.groups.get(g)){int r=m.resource[i];if(used.get(r)||m.hi[i].signum()==0)continue;used.set(r);p[i]=BigInteger.ONE;enumerate(m,g+1,used,p,out);p[i]=BigInteger.ZERO;used.clear(r);}
 }
 static PlanningBudget budget(){return new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);}
 static List<ExactLinearProgram.Constraint> run(Model m,boolean cold){var b=budget();b.proofJournal(new CountProof.Journal(4L<<20));List<ExactLinearProgram.Constraint> c;
  if(cold){try(var h=new ColdHall(m.rows,m.lo,m.hi,b)){while(!h.step()){}c=h.cuts();}oldWork+=b.nodes();}
  else{try(var h=new CountHall(m.rows,m.lo,m.hi,b)){while(!h.step()){}c=h.cuts();}newWork+=b.nodes();}
  check(b.reservedBytes()==0,"leak");return c;
 }
 static void verify(Model m,String name){var truth=solutions(m);var old=run(m,true);var next=run(m,false);models++;cuts+=next.size();
  for(var cut:next)for(var p:truth){BigInteger sum=BigInteger.ZERO;for(var e:cut.terms().entrySet())sum=sum.add(e.getValue().multiply(p[e.getKey()]));check(sum.compareTo(cut.upper())<=0,"unsound "+name+" "+cut);}
  check(new HashSet<>(next).containsAll(old),"lost filtering "+name+" old="+old+" new="+next);
 }
 static void lifecycle(){var m=model(new int[][]{{0,1},{0,1},{0,1,2,3},{2,3,4}},5,null);for(int cap=1;cap<160;cap++){final int stop=cap*5;var steps=new java.util.concurrent.atomic.AtomicInteger();var b=new PlanningBudget(0,20_000_000,1L<<20,()->steps.incrementAndGet()>=stop,System::nanoTime);try(var h=new CountHall(m.rows,m.lo,m.hi,b)){while(!h.step()){}}catch(java.util.concurrent.CancellationException ok){}check(b.reservedBytes()==0,"cancel leak");}for(int cap=1;cap<=24;cap++){var b=new PlanningBudget(0,20_000_000,cap*1024L,()->false,System::nanoTime);try(var h=new CountHall(m.rows,m.lo,m.hi,b)){while(!h.step()){}}check(b.reservedBytes()==0,"memory leak");}}
 public static void main(String[]args)throws Exception{
  // All 2-4-group graphs on 2-4 resources with at least two edges per group.
  for(int seed=0;seed<2500;seed++){Random r=new Random(seed*379L);int groups=2+r.nextInt(3),resources=2+r.nextInt(5);int[][] edges=new int[groups][];for(int g=0;g<groups;g++){edges[g]=new int[2+r.nextInt(2)];for(int i=0;i<edges[g].length;i++)edges[g][i]=r.nextInt(resources);}verify(model(edges,resources,r),"random "+seed);}
  verify(model(new int[][]{{0,1},{0,1},{0,1,2,3},{2,3,4}},5,null),"free resources");
  verify(model(new int[][]{{0,1},{0,1},{0,1,2},{0,1,2,3}},4,null),"nested Hall");
  lifecycle();var report=Map.of("models",models,"assertions",checks,"cuts",cuts,"old_work",oldWork,"new_work",newWork);Files.writeString(Path.of(args[0],"hall-probe.json"),new Gson().toJson(report));System.out.println(report);
 }
}

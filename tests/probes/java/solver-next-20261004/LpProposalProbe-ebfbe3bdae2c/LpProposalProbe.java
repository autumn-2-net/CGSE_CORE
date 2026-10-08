package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import java.io.*;
import java.nio.file.*;
import com.google.gson.Gson;
public class LpProposalProbe {
    static final Gson JSON=new Gson();
    record Model(String id,int n,List<ExactLinearProgram.Constraint> rows,BigInteger[] objective,ExactRational[] optimum){}
    static BigInteger b(long n){return BigInteger.valueOf(n);}
    static Model generated(int n,int bits,int seed){
        Random random=new Random(314159L+seed);var rows=new ArrayList<ExactLinearProgram.Constraint>();BigInteger[] cost=new BigInteger[n],cap=new BigInteger[n];ExactRational[] optimum=new ExactRational[n];
        for(int i=0;i<n;i++){cap[i]=b(2+random.nextInt(8));optimum[i]=ExactRational.of(cap[i]);cost[i]=b(1+random.nextInt(9)).shiftLeft(seed%2==0?bits:0);BigInteger scale=BigInteger.ONE.shiftLeft(bits+random.nextInt(8));rows.add(new ExactLinearProgram.Constraint(Map.of(i,scale),scale.multiply(cap[i])));rows.add(new ExactLinearProgram.Constraint(Map.of(i,scale.negate()),scale.multiply(cap[i].shiftRight(1)).negate()));}
        for(int k=0;k<Math.min(24,n/2);k++){var terms=new LinkedHashMap<Integer,BigInteger>();BigInteger rhs=BigInteger.ZERO;BigInteger scale=BigInteger.ONE.shiftLeft(bits+random.nextInt(8));for(int j=0;j<Math.min(12,n);j++){int id=random.nextInt(n);int coefficient=random.nextInt(9)-4;if(coefficient!=0)terms.put(id,b(coefficient).multiply(scale));}for(var e:terms.entrySet())rhs=rhs.add(e.getValue().multiply(cap[e.getKey()]));rows.add(new ExactLinearProgram.Constraint(terms,rhs.add(scale.multiply(b(random.nextInt(3))))));}
        Collections.shuffle(rows,random);return new Model("n"+n+"-bits"+bits+"-s"+seed,n,rows,cost,optimum);
    }
    static ExactRational objective(Model model,ExactRational[] point){ExactRational value=ExactRational.ZERO;for(int i=0;i<model.n;i++)value=value.add(point[i].multiply(ExactRational.of(model.objective[i])));return value;}
    static void verify(Model model,ExactRational[] point){if(point==null)throw new AssertionError("no point");for(var v:point)if(v.signum()<0)throw new AssertionError("negative point");for(var row:model.rows){var sum=ExactRational.ZERO;for(var term:row.terms().entrySet())sum=sum.add(point[term.getKey()].multiply(ExactRational.of(term.getValue())));if(sum.compareTo(ExactRational.of(row.upper()))>0)throw new AssertionError("original row violated");}if(model.optimum!=null&&!objective(model,point).equals(objective(model,model.optimum)))throw new AssertionError("wrong exact objective");}
    static Map<String,Object> solve(Model model,boolean proposalOnly){var budget=new PlanningBudget(60000,20000000,256L<<20,()->false,System::nanoTime);long start=System.nanoTime();boolean proposed=false,reconstructed=false;String status="NONE",error="";try{
        if(proposalOnly){int[] basis=CountLpProposal.propose(model.n,model.rows,model.objective,budget,262144);proposed=basis!=null;if(proposed)try(var exact=new ExactRevisedProgram(model.n,model.rows,model.objective,budget)){reconstructed=exact.reconstruct(basis);if(reconstructed){while(!exact.step()){}status=exact.result().toString();if(status.equals("OPTIMAL"))verify(model,exact.point());}}}
        else try(var exact=new ExactLinearProgram(model.n,model.rows,model.objective,budget)){while(!exact.step()){}status=exact.result().toString();if(status.equals("OPTIMAL"))verify(model,exact.point());}
    }catch(Throwable e){error=e.toString();status="ERROR";}
        var row=new LinkedHashMap<String,Object>();row.put("id",model.id);row.put("direct",proposalOnly);row.put("proposed",proposed);row.put("reconstructed",reconstructed);row.put("status",status);row.put("work",budget.nodes());row.put("ms",(System.nanoTime()-start)/1e6);row.put("peakBytes",budget.peakBytes());row.put("leak",budget.reservedBytes());row.put("error",error);row.put("diagnostics",budget.diagnostics());if(budget.reservedBytes()!=0)throw new AssertionError("memory leak");return row;}
    static List<Model> adversarial(){var out=new ArrayList<Model>();BigInteger a=BigInteger.ONE.shiftLeft(100),zero=BigInteger.ZERO;
        out.add(new Model("rounded-infeasible",2,List.of(new ExactLinearProgram.Constraint(Map.of(0,a),a),new ExactLinearProgram.Constraint(Map.of(0,a.negate()),a.negate().subtract(BigInteger.ONE)),new ExactLinearProgram.Constraint(Map.of(1,BigInteger.ONE),BigInteger.ONE)),new BigInteger[]{BigInteger.ONE,zero},null));
        out.add(new Model("rounded-feasible",2,List.of(new ExactLinearProgram.Constraint(Map.of(0,a),a.add(BigInteger.ONE)),new ExactLinearProgram.Constraint(Map.of(0,a.negate()),a.negate().subtract(BigInteger.ONE)),new ExactLinearProgram.Constraint(Map.of(1,BigInteger.ONE),BigInteger.ONE)),new BigInteger[]{BigInteger.ONE,zero},new ExactRational[]{new ExactRational(a.add(BigInteger.ONE),a),ExactRational.ZERO}));
        out.add(new Model("double-overflow",2,List.of(new ExactLinearProgram.Constraint(Map.of(0,BigInteger.ONE.shiftLeft(1100)),BigInteger.ONE.shiftLeft(1101)),new ExactLinearProgram.Constraint(Map.of(1,BigInteger.ONE),BigInteger.ONE)),new BigInteger[]{BigInteger.ONE,BigInteger.ONE},new ExactRational[]{ExactRational.of(b(2)),ExactRational.ONE}));
        out.add(new Model("empty-row-infeasible",2,List.of(new ExactLinearProgram.Constraint(Map.of(),a.negate())),new BigInteger[]{zero,zero},null));
        return out;
    }
    public static void main(String[] args)throws Exception{try(var out=new PrintWriter(Files.newBufferedWriter(Path.of(args[0])))){
        for(int n:new int[]{4,16,64,96})for(int bits:new int[]{0,64,160,512})for(int seed=0;seed<2;seed++){var model=generated(n,bits,seed);for(boolean direct:new boolean[]{true,false}){var row=solve(model,direct);out.println(JSON.toJson(row));out.flush();System.out.println(model.id+" direct="+direct+" "+row.get("status")+" work="+row.get("work"));}}
        for(var model:adversarial())for(boolean direct:new boolean[]{true,false}){var row=solve(model,direct);out.println(JSON.toJson(row));out.flush();System.out.println(model.id+" direct="+direct+" "+row.get("status")+" work="+row.get("work"));}
    }}
}

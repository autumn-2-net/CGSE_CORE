package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import static org.cgse.core.GeneralSearchReview.*;

public class RecoveryViewsReview {
    static void verify(List<GraphRecipe<String>> recipes, Map<String,Long> stock, String target, long amount, GraphPlan<String> plan) {
        Map<String,GraphRecipe<String>> primitives=new HashMap<>();recipes.forEach(r->primitives.put(r.id(),r));
        var s=GraphFixtureRegression.summary(plan.steps(),primitives,new IdentityHashMap<>());
        Map<String,BigInteger> funded=exact(stock);plan.missingExact().forEach((k,v)->funded.merge(k,v,BigInteger::add));
        for(var e:s.need().entrySet())check(funded.getOrDefault(e.getKey(),z(0)).compareTo(e.getValue())>=0,"prefix "+e);
        check(funded.getOrDefault(target,z(0)).add(s.delta().getOrDefault(target,z(0))).compareTo(z(amount))>=0,"goal");
    }
    static void entryAndLong() {
        var base=List.of(r("start",Map.of("c",1L,"f",1L),Map.of("p",2L)),
                r("mid",Map.of("p",3L),Map.of("q",1L)),r("end",Map.of("q",2L),Map.of("c",3L,"g",1L)));
        for(int mode=0;mode<4;mode++)for(long amount:new long[]{1,1000000000L,Long.MAX_VALUE/3}) {
            var recipes=new ArrayList<>(base);
            if((mode&1)!=0)recipes.add(r("alias",base.get(0).inputs(),base.get(0).outputs()));
            if((mode&2)!=0)recipes.add(r("burn",Map.of("q",2L),Map.of("g",1L)));
            Map<String,Long> stock=Map.of("c",3L,"f",3*amount,"p",1L);
            var b=budget();var plan=new GraphPlanner<>(new GraphCompiler<>(recipes)).plan("g",amount,stock,false,true,b);
            check(plan.feasible(),"long recovery "+mode+" "+amount+" "+b.diagnostics());verify(recipes,stock,"g",amount,plan);
            System.out.println("RECOVERY_LONG mode="+mode+" amount="+amount+" work="+b.nodes());
        }
        for(int seed=0;seed<16;seed++) {
            var recipes=new ArrayList<>(base);Collections.shuffle(recipes,new Random(seed));
            var stock=Map.of("c",2L,"f",2L,"p",2L);var b=budget();
            var plan=new GraphPlanner<>(new GraphCompiler<>(recipes)).plan("g",1,stock,false,true,b);
            check(plan.feasible(),"primitive entry was lost "+b.diagnostics());verify(recipes,stock,"g",1,plan);
        }
        System.out.println("PARTIAL_ENTRY 16 permutations passed");
    }
    static void localQuota() {
        Map<Integer,BigInteger> terms=new LinkedHashMap<>(),opposite=new LinkedHashMap<>();
        BigInteger goal=z(0);var random=new Random(19248);
        for(int i=0;i<24;i++){BigInteger w=z(100+random.nextInt(1000));terms.put(i,w);opposite.put(i,w.negate());if(i%3==0)goal=goal.add(w);}
        var rows=List.of(new ExactLinearProgram.Constraint(terms,goal),new ExactLinearProgram.Constraint(opposite,goal.negate()));
        BigInteger[] low=new BigInteger[24],high=new BigInteger[24];Arrays.fill(low,z(0));Arrays.fill(high,z(1));
        var b=budget();
        try(var limited=new CountMeetInMiddle(rows,low,high,b,2048)) {
            while(!limited.step()){}
            check(limited.counts()==null&&!limited.infeasible(),"local cutoff became proof");
            check(b.nodes()<=2050,"local quota did not yield "+b.nodes());
        }
        check(b.reservedBytes()==0,"matching quota workspace leak");
        try(var full=new CountMeetInMiddle(rows,low,high,b)) {
            while(!full.step()){}check(full.counts()!=null,"full strategy lost witness");
            BigInteger sum=z(0);for(var e:terms.entrySet())sum=sum.add(e.getValue().multiply(full.counts()[e.getKey()]));
            check(sum.equals(goal),"inexact witness");
        }
        check(b.reservedBytes()==0,"matching workspace leak");
        System.out.println("MATCHING_QUOTA incomplete proof rejected; full witness verified; work="+b.nodes());
    }
    static void finiteNetworks() {
        var random=new Random(927660);int sat=0,unsat=0;
        for(int test=0;test<1000;test++) {
            var recipes=new ArrayList<GraphRecipe<String>>();var stock=new HashMap<String,Long>();
            for(int i=0,n=2+random.nextInt(5);i<n;i++)stock.merge("k"+random.nextInt(4),1L,Long::sum);
            for(int i=0,n=3+random.nextInt(6);i<n;i++) {
                var in=new HashMap<String,Long>();var out=new HashMap<String,Long>();
                for(int j=0,m=1+random.nextInt(3);j<m;j++){in.merge("k"+random.nextInt(4),1L,Long::sum);out.merge("k"+random.nextInt(4),1L,Long::sum);}
                recipes.add(r("r"+i,in,out));
            }
            if(test%2==0){var first=recipes.get(0);recipes.add(r("alias",first.inputs(),first.outputs()));}
            long amount=1+random.nextInt(4);boolean reference=bfs(recipes,exact(stock),Map.of("k3",z(amount)));
            var b=budget();var plan=new GraphPlanner<>(new GraphCompiler<>(recipes)).plan("k3",amount,stock,false,false,b);
            check(!Set.of(GraphPlan.Result.UNKNOWN,GraphPlan.Result.TIMEOUT,GraphPlan.Result.SEARCH_LIMIT).contains(plan.result()),"unresolved finite "+test+" "+b.diagnostics());
            check(plan.feasible()==reference,"finite mismatch "+test+" "+plan.result()+" "+b.diagnostics());
            if(plan.feasible()||!plan.missingExact().isEmpty())verify(recipes,stock,"k3",amount,plan);
            if(reference)sat++;else unsat++;
        }
        System.out.println("RECOVERY_FINITE networks=1000 sat="+sat+" unsat="+unsat+" mismatches=0");
    }
    public static void main(String[]args){entryAndLong();localQuota();finiteNetworks();}
}

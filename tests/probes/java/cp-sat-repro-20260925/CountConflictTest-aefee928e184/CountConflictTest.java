package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Checks every learned conjunction against a separately enumerated integer model. */
public final class CountConflictTest {
    static final BigInteger ZERO=BigInteger.ZERO, ONE=BigInteger.ONE;
    static ExactLinearProgram.Constraint r(int bound,long... terms){var map=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<terms.length;i++)if(terms[i]!=0)map.put(i,BigInteger.valueOf(terms[i]));return new ExactLinearProgram.Constraint(map,BigInteger.valueOf(bound));}
    static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static boolean valid(List<ExactLinearProgram.Constraint> rows,BigInteger[] x){
        for(var row:rows){BigInteger sum=ZERO;for(var e:row.terms().entrySet())sum=sum.add(e.getValue().multiply(x[e.getKey()]));if(sum.compareTo(row.upper())>0)return false;}return true;
    }
    static PlanningBudget b(){return new PlanningBudget(0,2_000_000,32L<<20,()->false,System::nanoTime);}
    static GraphRecipe<String> recipe(String name,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(name,name,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
    static ExactLinearProgram.Constraint bound(int id,int amount,boolean minimum){return new ExactLinearProgram.Constraint(Map.of(id,minimum?ONE.negate():ONE),BigInteger.valueOf(minimum?-amount:amount));}
    static void branchReuse(){
        var compiler=new GraphCompiler<>(List.of(recipe("x",Map.of("raw",6L),Map.of("X",1L)),recipe("y",Map.of("raw",5L),Map.of("Y",1L)),
                recipe("join",Map.of("X",1L,"Y",1L),Map.of("P",1L)),recipe("direct",Map.of("raw",1L),Map.of("P",1L))));
        var work=b();var stock=Map.of("raw",10L);
        try(var model=RecipeCountModel.create(compiler,"P",1,stock,Map.of(),Set.of(),Set.of(),true,work)){
            int x=-1,y=-1,direct=-1;for(int i=0;i<model.recipes.size();i++){switch(model.recipes.get(i).id()){case "x"->x=i;case "y"->y=i;case "direct"->direct=i;}}
            var assumptions=List.of(bound(x,1,true),bound(direct,4,false),bound(y,1,true));
            var all=new ArrayList<>(model.constraints);all.addAll(assumptions);CountConflict learned;
            try(var bounds=new CountBounds(model.recipes.size(),all,work,model.constraints.size())){
                while(!bounds.step()){}require(bounds.blocked(),"Recipe budget should conflict");
                var proof=bounds.conflictingAssumptions();var used=new ArrayList<ExactLinearProgram.Constraint>();
                for(int i=proof.nextSetBit(0);i>=0;i=proof.nextSetBit(i+1))used.add(assumptions.get(i));
                require(!used.contains(assumptions.get(1)),"Learned irrelevant alternative upper bound");learned=new CountConflict(used);
            }
            try(var branch=new IntegerCountBranch<>(model,"P",1,stock,Map.of(),Set.of(),true,true,work,System.nanoTime(),List.of(bound(x,2,true),bound(y,1,true)))){
                branch.run(1024,List.of(),List.of(),List.of(learned),null,()->false);
                require(branch.state==IntegerCountBranch.State.DEAD&&branch.choicePruned&&!branch.initialized,"Did not skip propagation using learned core");
            }
            try(var sibling=new IntegerCountBranch<>(model,"P",1,stock,Map.of(),Set.of(),true,true,work,System.nanoTime(),List.of(bound(y,1,true)))){
                sibling.run(1024,List.of(),List.of(),List.of(learned),null,()->false);
                require(!sibling.choicePruned&&sibling.state!=IntegerCountBranch.State.DEAD,"Learned core killed feasible weaker sibling");
            }
        }
        require(work.reservedBytes()==0,"Shared conflict test leaked memory");
        System.out.println("Actual integer branches: learned resource core skips stronger sibling before propagation; feasible weaker sibling preserved");
    }
    public static void main(String[] args){
        var rows=List.of(r(1,1,1,0),r(-1,-1,0,0),r(-100,0,0,-1),r(-1,0,-1,0));
        var budget=b();try(var bounds=new CountBounds(3,rows,budget,1)){
            while(!bounds.step()){}
            require(bounds.blocked(),"Missed resource conflict");
            var proof=bounds.conflictingAssumptions();require(proof.equals(BitSet.valueOf(new long[]{5})),"Included unrelated decision: "+proof);
            var conflict=new CountConflict(List.of(rows.get(1),rows.get(3)));
            require(conflict.impliedBy(new BigInteger[]{ONE,ONE,ZERO},new BigInteger[]{null,null,null},budget),"Failed to reuse conflict");
            require(!conflict.impliedBy(new BigInteger[]{ZERO,ONE,ZERO},new BigInteger[]{null,null,null},budget),"Pruned legal weaker choice");
        }
        require(budget.reservedBytes()==0,"Memory leaked");
        try(var bounds=new CountBounds(2,List.of(r(7,6,10),r(-7,-6,-10)),b(),0)){
            while(!bounds.step()){}require(bounds.blocked(),"Missed integer GCD contradiction");
        }
        try(var bounds=new CountBounds(2,List.of(r(4,1,2),r(-4,-1,-2),r(1,1,0),r(-1,-1,0)),b(),2)){
            while(!bounds.step()){}require(bounds.blocked(),"Missed residue after fixing a variable");
        }
        var random=new Random(49831);int proofs=0;
        for(int test=0;test<1200;test++){
            var global=new ArrayList<ExactLinearProgram.Constraint>();for(int i=0;i<3;i++){long[] unit=new long[3];unit[i]=1;global.add(r(4,unit));}
            for(int row=0;row<4;row++){long[] terms=new long[3];for(int i=0;i<3;i++)terms[i]=random.nextInt(7)-3;global.add(r(random.nextInt(21)-6,terms));}
            var assumptions=new ArrayList<ExactLinearProgram.Constraint>();
            for(int row=0;row<5;row++){long[] terms=new long[3];int id=random.nextInt(3),sign=random.nextBoolean()?1:-1;terms[id]=sign;assumptions.add(r(sign*random.nextInt(6),terms));}
            var all=new ArrayList<>(global);all.addAll(assumptions);var work=b();
            try(var bounds=new CountBounds(3,all,work,global.size())){
                while(!bounds.step()){}
                var lower=bounds.lowerBounds();var upper=bounds.upperBounds();var core=new ArrayList<>(global);
                var proof=bounds.conflictingAssumptions();
                if(bounds.blocked()){
                    require(proof!=null,"Blocked without explanation");proofs++;
                    for(int i=proof.nextSetBit(0);i>=0;i=proof.nextSetBit(i+1))core.add(assumptions.get(i));
                } else require(proof==null,"Learned an unproved conflict");
                for(int a=0;a<=4;a++)for(int c=0;c<=4;c++)for(int d=0;d<=4;d++){
                    var x=new BigInteger[]{BigInteger.valueOf(a),BigInteger.valueOf(c),BigInteger.valueOf(d)};
                    if(bounds.blocked())require(!valid(core,x),"Unsound reduced conflict case "+test+" proof="+proof);
                    if(valid(all,x)){
                        require(!bounds.blocked(),"Pruned feasible model");
                        for(int i=0;i<3;i++)require(x[i].compareTo(lower[i])>=0&&(upper[i]==null||x[i].compareTo(upper[i])<=0),"Unsound bound case "+test);
                    }
                }
            }
            require(work.reservedBytes()==0,"Per-case workspace leak");
        }
        branchReuse();
        System.out.println("Count conflict: 1200 exhaustive models passed, verified reduced proofs="+proofs+"; GCD/fixed residues and irrelevant-decision removal passed");
    }
}

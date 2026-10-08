// Local isolated-server test only. Requires the same native AE fixture at x=0..3,y=65.
// Run inventoryreplanprepare, wait >=3s for the new cell to mount, then inventoryreplanstart.
// Exercises actual storage events, pattern registration, CPU ownership and physical input chest IO.
var invReplanState = null;
var IRPos = Java.loadClass('net.minecraft.core.BlockPos');
var IRKey = Java.loadClass('appeng.api.stacks.AEItemKey');
var IRSource = Java.loadClass('appeng.me.helpers.MachineSource');
var IRAction = Java.loadClass('appeng.api.config.Actionable');
var IRPattern = Java.loadClass('appeng.api.crafting.PatternDetailsHelper');
var IRStack = Java.loadClass('appeng.api.stacks.GenericStack');
function irBlock(level,x,z) { return level.getBlockEntity(new IRPos(x,65,z)); }
function irKey(id) { return IRKey['of(net.minecraft.world.item.ItemStack)'](Item.of('minecraft:'+id)); }
function irController(level) { return irBlock(level,1,0).getCluster().craftingLogic.gtlcore$graphController(); }
function irGrid(level) { return irBlock(level,1,0).getMainNode().getNode().getGrid(); }
function irField(object,name) { var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object); }
function irFailures(graph) { return Number(irField(irField(graph,'replanning'),'retry').failures()); }
function irPattern(inputs,output) { return IRPattern.encodeProcessingPattern(inputs.map(entry=>typeof entry==='string'?new IRStack(irKey(entry),1):new IRStack(irKey(entry[0]),entry[1])),[new IRStack(irKey(output),1)]); }
function irStock(level,id) { return Number(irGrid(level).getStorageService().getInventory().getAvailableStacks().get(irKey(id))); }
function irChange(level,id,insert,amount) {
 if(amount===undefined)amount=1;
 var inv=irGrid(level).getStorageService().getInventory(),source=new IRSource(irBlock(level,1,0));
 var n=insert?inv.insert(irKey(id),amount,IRAction.MODULATE,source):inv.extract(irKey(id),amount,IRAction.MODULATE,source);
 if(n!==amount)throw new Error('Storage '+(insert?'insert':'extract')+' rejected '+id+': '+n+'/'+amount);
}
function irChestCount(level,id) {
 var chest=irBlock(level,3,0),n=0;for(var i=0;i<chest.getContainerSize();i++)if(chest.getItem(i).id==='minecraft:'+id)n+=chest.getItem(i).getCount();return n;
}
function irConsume(level,id) {
 var chest=irBlock(level,3,0);for(var i=0;i<chest.getContainerSize();i++)if(chest.getItem(i).id==='minecraft:'+id){chest.removeItem(i,1);chest.setChanged();return;}
 throw new Error('Physical input missing '+id);
}
ServerEvents.tick(event=>{
 var s=invReplanState;if(s===null)return;
 try {
  var level=event.server.overworld(),graph=irController(level);s.tick++;
  if(s.tick>2400)throw new Error('Timeout phase='+s.phase+' reason='+(irField(graph,'runtime')===null?'no runtime':irField(graph,'runtime').reason()));
  if(s.phase==='calculate') {
   if(!s.future.isDone())return;var plan=s.future.get();if(plan.simulation())throw new Error('Initial complete plan simulated');
   var result=irGrid(level).getCraftingService().submitJob(plan,null,irBlock(level,1,0).getCluster(),false,new IRSource(irBlock(level,1,0)));
   if(!result.successful())throw new Error('Initial submit rejected '+result.errorCode());
   s.phase='flight';return;
  }
  if(s.phase==='flight') {
   if(graph.waiting(irKey('iron_ingot'))!==1||irChestCount(level,'stone')!==1)return;
   irBlock(level,2,0).getLogic().getPatternInv().setItemDirect(1,irPattern(['iron_ingot',['coal',2]],'gold_ingot'));
   s.phase='failure';return;
  }
  var failures=irFailures(graph);
  if(s.phase==='failure') {
   if(failures===0)return;
   if(String(irField(graph,'runtime').reason()).indexOf('REPLAN_MISSING_INPUT')!==0)throw new Error('Expected missing-coal replan failure');
   s.firstFailures=failures;s.phase='unrelated';s.since=s.tick;s.failureTicks.push(s.tick);
   console.info('[Inventory Replan] first missing-coal failure tick='+s.tick+' required_coal=2');return;
  }
  if(s.phase==='unrelated') {
   var quartz=irStock(level,'quartz');s.quartzMin=Math.min(s.quartzMin,quartz);s.quartzMax=Math.max(s.quartzMax,quartz);
   // Hold each amount across ten server ticks: same-tick insert/extract can legitimately coalesce.
   if((s.tick-s.since-1)%10===0){s.quartzPresent=!s.quartzPresent;irChange(level,'quartz',s.quartzPresent);s.unrelatedEvents++;}
   if(failures!==s.firstFailures)throw new Error('Unrelated quartz changes restarted failed replan');
   if(s.tick-s.since<120)return;
   if(s.quartzMin!==0||s.quartzMax!==1||s.quartzPresent)throw new Error('Unrelated stock transitions were not observable as intended');
   s.phase='related';s.since=s.tick;s.lastFailures=failures;
   console.info('[Inventory Replan] unrelated stock transitions='+s.unrelatedEvents+' observed_quartz='+s.quartzMin+'..'+s.quartzMax+' failures_unchanged='+failures);return;
  }
  if(s.phase==='related') {
   var coal=irStock(level,'coal');s.coalMin=Math.min(s.coalMin,coal);s.coalMax=Math.max(s.coalMax,coal);
   if(coal<0||coal>1)throw new Error('Partial coal stock escaped 0..1 fixture range');
   // The revised suffix requires two coal. Availability 0/1 is visible for ten ticks but never sufficient.
   if((s.tick-s.since-1)%10===0){s.coalPresent=!s.coalPresent;irChange(level,'coal',s.coalPresent);s.relatedEvents++;}
   if(failures>s.lastFailures){
    if(failures!==s.lastFailures+1)throw new Error('More than one replan attempt observed per tick');
    var requiredDelay=Math.min(600,40*Math.pow(2,Math.min(4,s.lastFailures-1)));
    var elapsed=s.tick-s.failureTicks[s.failureTicks.length-1];if(elapsed<requiredDelay)throw new Error('Retry backoff bypassed '+elapsed+' < '+requiredDelay);
    console.info('[Inventory Replan] retry_observed failures='+failures+' elapsed_ticks='+elapsed+' minimum_delay='+requiredDelay+' coal='+coal+'/2');
    s.lastFailures=failures;s.failureTicks.push(s.tick);
   }
   if(s.tick-s.since<160)return;
   if(s.coalMin!==0||s.coalMax!==1)throw new Error('Related stock transitions were not observable as intended');
   if(failures<=s.firstFailures||failures>s.firstFailures+3)throw new Error('Related-event retry count outside bounded backoff: '+failures);
   if(s.coalPresent){irChange(level,'coal',false);s.coalPresent=false;}
   s.totalFailed=failures;irChange(level,'coal',true,2);s.phase='recover';s.since=s.tick;
   console.info('[Inventory Replan] related stock transitions='+s.relatedEvents+' observed_coal='+s.coalMin+'..'+s.coalMax+'/2 failures='+failures+'; replenished_coal=2');return;
  }
  if(s.phase==='recover') {
   var replanning=irField(graph,'replanning');if(replanning.checkpoint()!==null||replanning.hasRequest())return;
   if(!graph.ownsTask()||graph.waiting(irKey('iron_ingot'))!==1)throw new Error('Original accepted iron ownership lost');
   if(irChestCount(level,'stone')!==1)throw new Error('In-flight physical stone changed during replanning');
   irConsume(level,'stone');irChange(level,'iron_ingot',true);s.phase='physical';return;
  }
  if(s.phase==='physical') {
   if(irChestCount(level,'iron_ingot')!==1||irChestCount(level,'coal')!==2)return;
   irConsume(level,'iron_ingot');irConsume(level,'coal');irConsume(level,'coal');irChange(level,'gold_ingot',true);s.phase='finish';return;
  }
  if(s.phase==='finish') {
   if(graph.ownsTask())return;var stock=irGrid(level).getStorageService().getInventory().getAvailableStacks();
   if(stock.get(irKey('gold_ingot'))!==1)throw new Error('Final gold not exactly one');
   for(var id of ['stone','iron_ingot','coal','quartz'])if(stock.get(irKey(id))!==0||irChestCount(level,id)!==0)throw new Error('Unexpected leftover '+id);
   if(irFailures(graph)!==0)throw new Error('Retry counter not reset after recovery');
   console.info('[Inventory Replan] PASS ticks='+s.tick+' unrelated_stock_transitions='+s.unrelatedEvents+' related_stock_transitions='+s.relatedEvents+' bounded_failures='+s.totalFailed+' consumed_coal=2 gold=1');
   invReplanState=null;
  }
 }catch(e){invReplanState=null;console.error('[Inventory Replan] FAIL '+e);}
});
ServerEvents.commandRegistry(event=>{
 function command(name,action){event.register(event.commands.literal(name).requires(s=>s.hasPermission(4)).executes(ctx=>{try{action(ctx.source.level,ctx.source.server);return 1;}catch(e){invReplanState=null;console.error('[Inventory Replan] FAIL '+e);return 0;}}));}
 command('inventoryreplanprepare',(level,server)=>{
  if(invReplanState!==null||irBlock(level,1,0).getCluster().isBusy())throw new Error('Fixture CPU busy');
  server.runCommandSilent('setblock 3 65 0 minecraft:air');server.runCommandSilent('setblock 3 65 0 minecraft:chest');
  irBlock(level,0,1).getInternalInventory().setItemDirect(0,Item.of('ae2:item_storage_cell_64k'));
  var inv=irBlock(level,2,0).getLogic().getPatternInv();for(var i=0;i<inv.size();i++)inv.setItemDirect(i,Item.of('minecraft:air'));
  inv.setItemDirect(0,irPattern(['stone'],'iron_ingot'));inv.setItemDirect(1,irPattern(['iron_ingot'],'gold_ingot'));
  console.info('[Inventory Replan] PREPARED');
 });
 command('inventoryreplanstart',level=>{
  if(invReplanState!==null)throw new Error('Already active');irChange(level,'stone',true);
  var source=new IRSource(irBlock(level,1,0)),strategy=Java.loadClass('appeng.api.networking.crafting.CalculationStrategy');
  invReplanState={phase:'calculate',tick:0,unrelatedEvents:0,relatedEvents:0,quartzPresent:false,coalPresent:false,quartzMin:2,quartzMax:-1,coalMin:2,coalMax:-1,failureTicks:[],future:irGrid(level).getCraftingService().beginCraftingCalculation(level,()=>source,irKey('gold_ingot'),1,strategy.REPORT_MISSING_ITEMS)};
  console.info('[Inventory Replan] STARTED');
 });
});

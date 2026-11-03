package de.robinthor.digiworldexplorer.strategy

import de.robinthor.digiworldexplorer.detection.Cell
import de.robinthor.digiworldexplorer.detection.CellScores
import java.util.PriorityQueue

enum class Direction(val dr:Int,val dc:Int){RIGHT(0,1),DOWN(1,0),UP(-1,0),LEFT(0,-1)}
enum class ActionKind{MOVE,ATTACK,DASH}
data class Action(val kind:ActionKind,val target:Cell,val direction:Direction,val reason:String)

object MovementPlanner{
 /** Pyramiden werden tidak lagi dihindari; langsung dihancurkan/diserang saat ditemui. */
 private const val OBSTACLE_COST=1
 const val CLAW_RESERVE=0
 const val LOOKAHEAD=3
 private const val DASH_PYRAMIDS=2
 private const val DASH_RESERVE=1
 private const val LOOKAHEAD_WEIGHT=20
 private const val BLOCKED_PENALTY=0
 private const val ITEM_SCORE=.06
 private const val ENERGY_ORANGE=.06
 private const val ENERGY_DETOUR=3
 private const val DEAD_END_PENALTY=250

 private data class Node(val cost:Int,val cell:Cell,val path:List<Action>):Comparable<Node>{override fun compareTo(o:Node)=cost.compareTo(o.cost)}

 fun choose(
  player:Cell,
  cells:Map<Cell,CellScores>,
  history:List<Cell>,
  dashAvailable:Boolean=false,
  preview:Map<Cell,CellScores> = emptyMap(),
  forbiddenObstacles:Set<Cell> = emptySet(),
  dashCharges:Int=0,
  stuck:Boolean=false,
  claws:Int?=null,
  settings:DwsNavigationSettings=DwsNavigationSettings(),
 ):Action?{
  val energyVisible=cells.any{(cell,score)->cell!=player&&score.orange>ENERGY_ORANGE}
  if(settings.dashSpamUntilZero&&!energyVisible&&dashAvailable&&dashCharges>0){
   return Action(ActionKind.DASH,Cell(player.row,minOf(4,player.col+LOOKAHEAD)),Direction.RIGHT,"Dash-Spam bis HUD 0")
  }

  // Selalu izinkan untuk langsung menghancurkan piramida (mayBreak = true)
  route(player,cells,history,preview,forbiddenObstacles,true,settings)?.let{return it}

  val ahead=(1..LOOKAHEAD).map{Cell(player.row,player.col+it)}.filter{it.col<=4}
  val pyramidsAhead=ahead.count{cells[it]?.obstacle()==true}
  if(dashAvailable&&dashCharges>DASH_RESERVE&&pyramidsAhead>=DASH_PYRAMIDS&&!energyVisible){
   return Action(ActionKind.DASH,Cell(player.row,minOf(4,player.col+LOOKAHEAD)),Direction.RIGHT,"erzwungener Ausweg: $pyramidsAhead Pyramiden")
  }

  if(dashAvailable&&dashCharges>0){
   return Action(ActionKind.DASH,Cell(player.row,minOf(4,player.col+LOOKAHEAD)),Direction.RIGHT,"Notausgang")
  }
  return null
 }
 
 private fun allowedDirections(settings:DwsNavigationSettings):List<Direction> = if(settings.allowLeft) Direction.entries else Direction.entries.filterNot{it==Direction.LEFT}

 private fun route(
  player:Cell,
  cells:Map<Cell,CellScores>,
  history:List<Cell>,
  preview:Map<Cell,CellScores>,
  forbiddenObstacles:Set<Cell>,
  mayBreak:Boolean,
  settings:DwsNavigationSettings,
 ):Action?{
  // Tidak ada sel piramida yang dimasukkan ke blockedCells secara paksa
  val blockedCells=forbiddenObstacles

  if(settings.forceForwardAttack){
   val energy=cells.filter{(cell,score)->cell!=player&&score.orange>ENERGY_ORANGE}.keys
   if(settings.betterEnergyCollect){
    val sameColumn=energy.filter{it.col==player.col}.toSet()
    forwardItemPath(player,sameColumn,cells,blockedCells,settings)?.firstOrNull()?.let{return it.copy(reason="Energie in eigener Spalte zuerst")}
   }
   forwardItemPath(player,energy,cells,blockedCells,settings)?.firstOrNull()?.let{return it.copy(reason="Energie direkt")}
   val right=Cell(player.row,player.col+1)
   cells[right]?.let{score->
    if(right !in forbiddenObstacles){
     return Action(if(score.obstacle())ActionKind.ATTACK else ActionKind.MOVE,right,Direction.RIGHT,if(score.obstacle())"Vorwaertshindernis angreifen" else "Nur vorwaerts")
    }
   }
   return null
  }

  itemPath(player,cells,blockedCells,settings)?.let{path->
   val goal=path.last().target
   val energy=(cells[goal]?.orange?:0.0)>ENERGY_ORANGE
   return path.first().copy(reason=if(energy)"Energie zuerst" else "item route")
  }

  val rightEdge=cells.keys.filter{it.col==4}.toSet()
  shortestPath(player,rightEdge,cells,forbiddenObstacles,settings)?.firstOrNull()?.let{return it.copy(reason="Durchbruch-Route")}

  val oscillating=history.size>=4&&history.takeLast(4).let{it[0]==it[2]&&it[1]==it[3]}
  val previous=history.dropLast(1).lastOrNull()
  val candidates=allowedDirections(settings).mapNotNull{d->
   val n=Cell(player.row+d.dr,player.col+d.dc);val scoreCell=cells[n]?:return@mapNotNull null;if(n in blockedCells)return@mapNotNull null
   val blocked=scoreCell.obstacle()
   var score=when(d){Direction.RIGHT->100;Direction.DOWN->20;Direction.UP->15;Direction.LEFT->-40}
   if(!blocked)score+=(scoreCell.highlight*20).toInt()
   score+=freeAhead(n,cells)*LOOKAHEAD_WEIGHT
   if(n==previous)score-=80;if(oscillating&&n==history[history.lastIndex-1])score-=200
   val look=preview[Cell(n.row,5)];if(look!=null&&look.pyramid>.17&&look.item<=.06)score-=35;if((look?.item?:0.0)>.06)score+=45
   score to Action(if(blocked)ActionKind.ATTACK else ActionKind.MOVE,n,d,if(blocked)"Pyramide zerschlagen" else "Strecke rechts")
  }
  
  return candidates.maxByOrNull{it.first}?.second
 }

 private fun freeAhead(from:Cell,cells:Map<Cell,CellScores>):Int{
  val ahead=(1..LOOKAHEAD).map{Cell(from.row,from.col+it)}.filter{it.col<=4}
  if(ahead.isEmpty())return LOOKAHEAD
  return ahead.size * LOOKAHEAD / ahead.size
 }

 private fun itemPath(player:Cell,cells:Map<Cell,CellScores>,blocked:Set<Cell>,settings:DwsNavigationSettings):List<Action>?{
  val items=cells.filter{(cell,s)->
   cell!=player&&s.item>ITEM_SCORE&&(!settings.collectOnlyEnergy||s.orange>ENERGY_ORANGE)
  }.keys
  if(items.isEmpty())return null
  val any=forwardItemPath(player,items,cells,blocked,settings)
  val energy=items.filter{(cells[it]?.orange?:0.0)>ENERGY_ORANGE}.toSet()
  if(energy.isEmpty())return any
  if(!settings.legacyV4Core && any?.size==1 && (cells[any.last().target]?.orange?:0.0)<=ENERGY_ORANGE &&
   forwardItemPath(player,energy,cells,blocked,settings)?.size?.let{it>1}==true)return any
  if(settings.betterEnergyCollect){
   val sameColumn=energy.filter{it.col==player.col}.toSet()
   forwardItemPath(player,sameColumn,cells,blocked,settings)?.let{return it}
  }
  val toEnergy=forwardItemPath(player,energy,cells,blocked,settings)?:return any
  if(any==null)return toEnergy
  return if(toEnergy.size<=any.size+ENERGY_DETOUR)toEnergy else any
 }

 private fun forwardItemPath(player:Cell,targets:Set<Cell>,cells:Map<Cell,CellScores>,blocked:Set<Cell>,settings:DwsNavigationSettings):List<Action>? =
  targets.mapNotNull{target->
   shortestPath(player,setOf(target),cells,blocked,settings)?.let{path->
    val backwards=(player.col-target.col).coerceAtLeast(0)
    Triple(path.size+backwards*6,-target.col,path)
   }
  }.minWithOrNull(compareBy<Triple<Int,Int,List<Action>>>{it.first}.thenBy{it.second})?.third

 private fun escapesRight(from:Cell,cells:Map<Cell,CellScores>,blocked:Set<Cell>,passObstacles:Boolean,settings:DwsNavigationSettings):Boolean{
  return true
 }

 private fun shortestPath(start:Cell,targets:Set<Cell>,cells:Map<Cell,CellScores>,forbidden:Set<Cell>,settings:DwsNavigationSettings):List<Action>?{
  val q=PriorityQueue<Node>();q+=Node(0,start,emptyList());val best=mutableMapOf(start to 0)
  while(q.isNotEmpty()){val n=q.remove();if(n.cost!=best[n.cell])continue;if(n.cell in targets&&n.path.isNotEmpty())return n.path
   for(d in allowedDirections(settings)){val next=Cell(n.cell.row+d.dr,n.cell.col+d.dc);if(next in forbidden)continue;val s=cells[next]?:continue;val obstacle=s.obstacle();val cost=n.cost+(if(obstacle)OBSTACLE_COST else 1)
    if(cost<(best[next]?:999)){best[next]=cost;q+=Node(cost,next,n.path+Action(if(obstacle)ActionKind.ATTACK else ActionKind.MOVE,next,d,"item route"))}}}
  return null
 }

 fun plan(
  player:Cell,
  cells:Map<Cell,CellScores>,
  history:List<Cell>,
  dashAvailable:Boolean=false,
  preview:Map<Cell,CellScores> = emptyMap(),
  forbiddenObstacles:Set<Cell> = emptySet(),
  dashCharges:Int=0,
  stuck:Boolean=false,
  claws:Int?=null,
  maxSteps:Int=1,
  settings:DwsNavigationSettings=DwsNavigationSettings(),
 ):List<Action>{
  val first=choose(player,cells,history,dashAvailable,preview,forbiddenObstacles,dashCharges,stuck,claws,settings)?:return emptyList()
  return listOf(first)
 }
}

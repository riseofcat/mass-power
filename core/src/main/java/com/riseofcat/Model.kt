package com.riseofcat

import com.google.gson.Gson
import com.n8cats.lib_gwt.DefaultValueMap
import com.n8cats.lib_gwt.LibAllGwt
import com.n8cats.lib_gwt.Signal
import com.n8cats.share.ClientPayload
import com.n8cats.share.Params
import com.n8cats.share.ServerPayload
import com.n8cats.share.ShareTodo
import com.n8cats.share.Tick
import com.n8cats.share.TickActions
import com.n8cats.share.data.Angle
import com.n8cats.share.data.BigAction
import com.n8cats.share.data.Car
import com.n8cats.share.data.Logic
import com.n8cats.share.data.PlayerAction
import com.n8cats.share.data.PlayerId
import com.n8cats.share.data.State
import com.n8cats.share.data.XY
import com.n8cats.share.redundant.ServerSayS
import com.riseofcat.reflect.Conf

import java.util.ArrayList
import java.util.HashMap

class Model(json:Gson,conf:Conf) {
  val client:PingClient<ServerPayload,ClientPayload>
  @Deprecated("") var copyTime:Long = 0
  @Deprecated("") var tickTime:Long = 0
  var playerId:PlayerId? = null
  private val actions = DefaultValueMap(HashMap<Tick,List<BigAction>>(),DefaultValueMap.ICreateNew {App.context.createConcurrentList()})
  private val myActions = DefaultValueMap(HashMap<Tick,List<Action>>(),DefaultValueMap.ICreateNew {ArrayList()})
  private var stable:StateWrapper? = null
  private var sync:Sync? = null
  val playerName:String
    get() = if(playerId==null) "Wait connection..." else "Player "+playerId!!.toString()
  private var previousActionId = 0
  val displayState:State?
    get() = if(sync==null) null else getState(sync!!.calcClientTck().toInt())
  private var cache:StateWrapper? = null

  class Sync(internal val serverTick:Float,oldSync:Sync?) {
    internal val clientTick:Float
    internal val time:Long

    init {
      time = App.timeMs()
      if(oldSync==null)
        this.clientTick = serverTick
      else
        this.clientTick = oldSync.calcClientTck()
    }

    private fun calcSrvTck(t:Long):Float {
      return serverTick+(t-time)/Logic.UPDATE_MS.toFloat()
    }

    fun calcSrvTck():Float {
      return calcSrvTck(App.timeMs())
    }

    fun calcClientTck():Float {
      val t = App.timeMs()
      return calcSrvTck(t)+(clientTick-serverTick)*(1f-LibAllGwt.Fun.arg0toInf((t-time).toDouble(),600f))
    }
  }

  init {
    client = PingClient(json,conf.host,conf.port,"socket",ServerSayS::class.java)
    client.connect(object:Signal.Listener<ServerPayload> {
      override fun onSignal(s:ServerPayload) {
        synchronized(this) {
          sync = Sync(s.tick+client.smartLatencyS/Logic.UPDATE_S,sync)
          if(s.welcome!=null) playerId = s.welcome!!.id
          if(s.stable!=null) {
            if(s.stable!!.state!=null)
              stable = StateWrapper(s.stable!!.state,s.stable!!.tick)
            else
              stable!!.tick(s.stable!!.tick)
            clearCache(s.stable!!.tick)
          }
          if(s.actions!=null&&s.actions!!.size()>0) {
            for(t in s.actions!!) {
              actions.getExistsOrPutDefault(Tick(t.tick)).addAll(t.list)
              clearCache(t.tick+1)
            }
          }
          for(t in myActions.map.keys) {
            val iterator = myActions.map[t].iterator()
            whl@ while(iterator.hasNext()) {
              val next = iterator.next()
              if(s.canceled!=null) {
                if(s.canceled!!.contains(next.aid)) {
                  iterator.remove()
                  clearCache(t.tick+1)
                  continue
                }
              }
              if(s.apply!=null) {
                for(apply in s.apply!!) {
                  if(apply.aid==next.aid) {
                    if(!ShareTodo.SIMPLIFY) actions.getExistsOrPutDefault(t.add(apply.delay)).add(PlayerAction(playerId!!,next.action).toBig())
                    iterator.remove()
                    clearCache(t.tick+1)
                    continue@whl
                  }
                }
              }
            }
          }
        }
      }
    })
  }

  fun ready():Boolean {
    return playerId!=null
  }

  fun action(action:com.n8cats.share.data.Action) {
    synchronized(this) {
      val clientTick = sync!!.calcClientTck().toInt()//todo +0.5f?
      if(!ready()) return
      if(false) if(sync!!.calcSrvTck()-sync!!.calcClientTck()>Params.DELAY_TICKS*1.5||sync!!.calcClientTck()-sync!!.calcSrvTck()>Params.FUTURE_TICKS*1.5) return
      val w = (client.smartLatencyS/Logic.UPDATE_S+1).toInt()//todo delta serverTick-clientTick
      val a = ClientPayload.ClientAction()
      a.aid = ++previousActionId
      a.wait = w
      a.tick = clientTick+w//todo serverTick?
      a.action = action
      synchronized(myActions) {
        myActions.getExistsOrPutDefault(Tick(clientTick+w)).add(Action(a.aid,a.action))
      }
      val payload = ClientPayload()
      payload.tick = clientTick
      payload.actions = ArrayList()
      payload.actions!!.add(a)
      client.say(payload)
    }
  }

  fun touch(pos:XY) {//todo move out?
    val displayState = displayState
    if(displayState==null||playerId==null) return
    for((owner,_,_,pos1) in displayState.cars) {
      if(playerId==owner) {
        val direction = pos.sub(pos1).calcAngle().add(Angle.degreesAngle((0*180).toFloat()))
        action(com.n8cats.share.data.Action(direction))
        break
      }
    }
  }

  fun update(graphicDelta:Float) {
    //	if(serverTickPreviousTime == null) return;
    //	float time = App.timeSinceCreate();
    //	serverTick += (time - serverTickPreviousTime) / Logic.UPDATE_S;
    //	serverTickPreviousTime = time;
    //	clientTick += graphicDelta / Logic.UPDATE_S;
    //	clientTick += (serverTick - clientTick) * LibAllGwt.Fun.arg0toInf(Math.abs((serverTick - clientTick) * graphicDelta), 6f);
  }

  private fun clearCache(tick:Int) {
    if(cache!=null&&tick<cache!!.tick) cache = null
  }

  private fun getNearestCache(tick:Int):StateWrapper? {
    return if(cache!=null&&cache!!.tick<=tick) cache else null
  }

  private fun saveCache(value:StateWrapper) {
    cache = value
  }

  private fun getState(tick:Int):State? {
    var result = getNearestCache(tick)
    var t:Long? = null
    if(result==null) {
      if(stable==null) return null
      synchronized(this) {
        result = StateWrapper(stable!!)
        saveCache(result)
      }
      t = App.timeMs()
    }
    result!!.tick(tick)
    if(t!=null) tickTime += App.timeMs()-t
    return result!!.state
  }

  fun dispose() {
    client.close()
  }

  private inner class Action(val aid:Int,action:com.n8cats.share.data.Action):PlayerAction(playerId,action)
  private inner class StateWrapper {
    var state:State
    var tick:Int = 0

    constructor(state:State,tick:Int) {
      this.state = state
      this.tick = tick
    }

    constructor(obj:StateWrapper) {
      val t = App.timeMs()
      state = obj.state.copy2()
      copyTime += App.timeMs()-t
      this.tick = obj.tick
    }

    fun tick(targetTick:Int) {
      while(tick<targetTick) {
        val other = actions.map[Tick(tick)]
        if(other!=null) state.act(other.iterator())
        val my = myActions.map[Tick(tick)]
        if(my!=null) {
          synchronized(myActions) {
            state.act(my.iterator())
          }
        }
        state.tick()
        tick++
      }
    }
  }
}
package io.github.billstark001.latticium.planning;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.dsl.Model.SetType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict schema reader. Errors include a JSON pointer; DSL errors preserve expression offsets. */
public final class ProfileReader {
    private final ObjectMapper mapper=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static final class Error extends IllegalArgumentException {
        public Error(String pointer,String message) { super(pointer+": "+message); }
    }
    public Profile read(String json) {
        try {
            JsonNode root=mapper.readTree(json);
            ObjectNode o=obj(root,""); keys(o,"", "schema","id","use","activation","scope","select","target","policy");
            if(integer(o,"/schema","schema",-1)!=1) throw new Error("/schema","Expected schema 1");
            var id=id(string(o,"/id","id"),"/id");
            var uses=new ArrayList<ResourceId>(); var use=o.get("use");
            if(use!=null) { if(!use.isArray()) throw new Error("/use","Expected array"); int i=0; for(var node:use) { if(!node.isTextual()) throw new Error("/use/"+i,"Expected string"); uses.add(id(node.asText(),"/use/"+i++)); } if(new HashSet<>(uses).size()!=uses.size()) throw new Error("/use","Duplicate import"); }
            Profile.Activation activation=null;
            if(o.has("activation")) {
                var a=obj(o.get("activation"),"/activation"); keys(a,"/activation","where","mode","initial");
                var mode=choice(string(a,"/activation/mode","mode"),Profile.Activation.Mode.class,"/activation/mode");
                var initial=optional(a,"initial","ignore","/activation/initial");
                if(!List.of("ignore","fire_if_inside").contains(initial)) throw new Error("/activation/initial","Invalid initial mode");
                activation=new Profile.Activation(string(a,"/activation/where","where"),mode,initial.equals("fire_if_inside"));
            }
            String scope=string(o,"/scope","scope"); var s=obj(o.get("select"),"/select"); keys(s,"/select","where","choose");
            var select=new Profile.Select(string(s,"/select/where","where"),choice(optional(s,"choose","nearest","/select/choose"),Profile.Select.Choose.class,"/select/choose"));
            var t=obj(o.get("target"),"/target"); int kinds=(t.has("items")?1:0)+(t.has("clear")?1:0)+(t.has("source")?1:0); if(kinds!=1) throw new Error("/target","Exactly one target form required");
            Profile.Target target;
            if(t.has("items")) {
                keys(t,"/target","items","states","choose"); var prefer=new ArrayList<ResourceId>(); var choose=t.get("choose");
                if(choose!=null) {
                    if(choose.isTextual()) { if(!choose.asText().equals("most_available")) throw new Error("/target/choose","Invalid choice"); }
                    else {var co=obj(choose,"/target/choose");keys(co,"/target/choose","prefer");var p=co.get("prefer");if(p==null||!p.isArray()||p.isEmpty())throw new Error("/target/choose/prefer","Expected nonempty array");int i=0;for(var item:p){if(!item.isTextual())throw new Error("/target/choose/prefer/"+i,"Expected string");prefer.add(id(item.asText(),"/target/choose/prefer/"+i++));}if(new HashSet<>(prefer).size()!=prefer.size())throw new Error("/target/choose/prefer","Duplicate item");}
                }
                target=new Profile.Items(string(t,"/target/items","items"),t.has("states")?string(t,"/target/states","states"):null,List.copyOf(prefer));
            } else if(t.has("clear")) { keys(t,"/target","clear"); if(!t.get("clear").isBoolean()||!t.get("clear").booleanValue())throw new Error("/target/clear","Expected true");target=new Profile.Clear(); }
            else { keys(t,"/target","source","using","include_air"); var air=t.get("include_air"); if(air!=null&&!air.isBoolean())throw new Error("/target/include_air","Expected boolean");target=new Profile.Source(id(string(t,"/target/source","source"),"/target/source"),t.has("using")?string(t,"/target/using","using"):null,air!=null&&air.booleanValue()); }
            var po=o.has("policy")?obj(o.get("policy"),"/policy"):mapper.createObjectNode(); keys(po,"/policy","break","max_actions_per_tick","max_actions_per_activation");
            var policy=new Profile.Policy(choice(optional(po,"break","deny","/policy/break"),Profile.Policy.BreakMode.class,"/policy/break"),integer(po,"/policy/max_actions_per_tick","max_actions_per_tick",1),integer(po,"/policy/max_actions_per_activation","max_actions_per_activation",256));
            return new Profile(1,id,List.copyOf(uses),activation,scope,select,target,policy);
        } catch(IOException ex) { throw new Error("",ex.getMessage()); }
    }
    public Profile.Bound bind(Profile p,Compiler compiler) {
        if(!p.uses().isEmpty())throw new Error("/use","Module resolver required");
        return bindLoaded(p,compiler);
    }
    public Profile.Bound bind(Profile p,Compiler compiler,ModuleLoader.Resolver modules) {
        try{new ModuleLoader(modules,compiler).loadAll(p.uses());}
        catch(IllegalArgumentException|io.github.billstark001.latticium.dsl.Syntax.Failure ex){throw new Error("/use",ex.getMessage());}
        return bindLoaded(p,compiler);
    }
    private Profile.Bound bindLoaded(Profile p,Compiler compiler) {
        try {
            var activation=p.activation()==null?null:at("/activation/where",()->compiler.compile(p.activation().where(),SetType.POS));
            var scope=at("/scope",()->compiler.compile(p.scope(),SetType.POS));
            var parsed=at("/scope",()->io.github.billstark001.latticium.dsl.Parser.expression(p.scope()));
            if(!finite(parsed)) throw new Error("/scope","Scope must have finite enumerable bounds (box, sphere or selection)");
            compiler.targetAvailable(p.target() instanceof Profile.Source);
            var select=at("/select/where",()->compiler.compile(p.select().where(),SetType.POS));
            Compiler.Bound items=null,states=null;
            if(p.target() instanceof Profile.Items t) {
                items=at("/target/items",()->compiler.compile(t.expression(),SetType.ITEM));
                if(t.states()!=null) states=at("/target/states",()->compiler.compile(t.states(),SetType.STATE));
            } else if(p.target() instanceof Profile.Source t && t.using()!=null) items=at("/target/using",()->compiler.compile(t.using(),SetType.ITEM));
            if(p.target() instanceof Profile.Clear && p.policy().breakMode()==Profile.Policy.BreakMode.DENY) throw new Error("/policy/break","Clear target cannot progress while breaking is denied");
            return new Profile.Bound(p,activation,scope,select,items,states);
        } catch(io.github.billstark001.latticium.dsl.Syntax.Failure ex) { throw new Error("/expression",ex.getMessage()); }
    }
    private interface Op<T> {T run();}
    private static <T>T at(String pointer,Op<T> operation) { try{return operation.run();}catch(io.github.billstark001.latticium.dsl.Syntax.Failure ex){throw new Error(pointer,ex.getMessage());} }
    private static boolean finite(io.github.billstark001.latticium.dsl.Syntax.Expr e) {
        if(e instanceof io.github.billstark001.latticium.dsl.Syntax.Call c) return List.of("box","sphere","selection").contains(c.name());
        if(e instanceof io.github.billstark001.latticium.dsl.Syntax.Binary b) return b.operator()=='&' ? finite(b.left())||finite(b.right()):finite(b.left())&&finite(b.right());
        return false;
    }
    private static ObjectNode obj(JsonNode n,String p) {if(!(n instanceof ObjectNode o))throw new Error(p,"Expected object");return o;}
    private static void keys(ObjectNode n,String p,String... allowed) {Set<String> valid=Set.of(allowed);n.fieldNames().forEachRemaining(k->{if(!valid.contains(k))throw new Error(p+"/"+k,"Unknown field");});}
    private static String string(ObjectNode n,String p,String key) {var v=n.get(key);if(v==null||!v.isTextual())throw new Error(p,"Expected string");return v.asText();}
    private static String optional(ObjectNode n,String key,String fallback,String p){return n.has(key)?string(n,p,key):fallback;}
    private static int integer(ObjectNode n,String p,String key,int fallback){var v=n.get(key);if(v==null)return fallback;if(!v.isIntegralNumber()||!v.canConvertToInt()||v.intValue()<=0 && !key.equals("schema"))throw new Error(p,"Expected positive integer");return v.intValue();}
    private static ResourceId id(String raw,String p){try{return ResourceId.parse(raw);}catch(IllegalArgumentException ex){throw new Error(p,ex.getMessage());}}
    private static <T extends Enum<T>> T choice(String raw,Class<T> type,String p){try{return Enum.valueOf(type,raw.toUpperCase());}catch(IllegalArgumentException ex){throw new Error(p,"Invalid choice: "+raw);}}
}

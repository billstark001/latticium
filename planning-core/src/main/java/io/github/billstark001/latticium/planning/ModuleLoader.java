package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.dsl.Parser;
import io.github.billstark001.latticium.dsl.Syntax;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Explicit, bounded import graph for declaration-only DSL modules. */
public final class ModuleLoader {
  private static final int MAX_IMPORT_DEPTH = 64;
  private static final int MAX_MODULES = 512;

  public record Module(String source, List<ResourceId> dependencies) {
    public Module {
      dependencies = List.copyOf(dependencies);
    }
  }

  public interface Resolver {
    /** Empty means that the requested declaration module does not exist. */
    Optional<Module> resolve(ResourceId id);
  }

  private final Resolver resolver;
  private final Compiler compiler;
  private final Set<ResourceId> loaded = new HashSet<>(), active = new HashSet<>();

  public ModuleLoader(Resolver resolver, Compiler compiler) {
    this.resolver = resolver;
    this.compiler = compiler;
  }

  /** Loads a dependency batch atomically; failed parsing or binding publishes no new names. */
  public void loadAll(List<ResourceId> ids) {
    var declarations = new ArrayList<Syntax.Declaration>();
    var functions = new ArrayList<Syntax.Function>();
    var pending = new HashSet<ResourceId>();
    for (var id : ids) load(id, 0, pending, declarations, functions);
    compiler.compile(new Syntax.Document(declarations, functions, List.of()));
    loaded.addAll(pending);
  }

  private void load(
      ResourceId id,
      int depth,
      Set<ResourceId> pending,
      List<Syntax.Declaration> declarations,
      List<Syntax.Function> functions) {
    if (loaded.contains(id) || pending.contains(id)) return;
    if (depth > MAX_IMPORT_DEPTH)
      throw new IllegalArgumentException("Import depth exceeded: " + id);
    if (active.contains(id)) throw new IllegalArgumentException("Cyclic module import: " + id);
    if (loaded.size() + pending.size() + active.size() >= MAX_MODULES)
      throw new IllegalArgumentException("Module count exceeded: " + id);
    active.add(id);
    try {
      var module =
          resolver
              .resolve(id)
              .orElseThrow(() -> new IllegalArgumentException("Missing module: " + id));
      for (var dependency : module.dependencies())
        load(dependency, depth + 1, pending, declarations, functions);
      Syntax.Document doc;
      try {
        doc = Parser.document(module.source());
      } catch (Syntax.Failure ex) {
        throw new Syntax.Failure(
            "Module " + id + ": " + ex.diagnostic().message(), ex.diagnostic().span());
      }
      if (!doc.terminals().isEmpty())
        throw new IllegalArgumentException("Module cannot execute query: " + id);
      long totalBindings =
          (long) declarations.size()
              + functions.size()
              + doc.declarations().size()
              + doc.functions().size();
      if (totalBindings > Compiler.MAX_TOP_LEVEL_BINDINGS)
        throw new IllegalArgumentException("Module binding budget exceeded: " + id);
      declarations.addAll(doc.declarations());
      functions.addAll(doc.functions());
      pending.add(id);
    } finally {
      active.remove(id);
    }
  }
}

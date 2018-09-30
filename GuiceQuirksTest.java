import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.inject.AbstractModule;
import com.google.inject.ConfigurationException;
import com.google.inject.CreationException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.Scope;
import com.google.inject.ScopeAnnotation;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Singleton;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class GuiceQuirksTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Before
  public void setUp() throws Exception {}

  //===========================================================================================
  // Example 1:  Attempt to bind an instance in a child module, when the class already has a
  // binding in the parent module.  This fails because you cannot override a parent module
  // binding in a child module.
  //===========================================================================================

  private interface A {
    void doA();
  }

  private static class AImpl implements A {
    @Override
    public void doA() {
      // empty
    }
  }

  private static class ParentModule extends AbstractModule {
    @Override
    protected void configure() {
      bind(A.class).to(AImpl.class);
    }
  }

  private static class ChildModule extends AbstractModule {
    private A instance;

    ChildModule(A instance) {
      this.instance = instance;
    }

    @Override
    protected void configure() {
      bind(A.class).toInstance(instance);
    }
  }

  @Test
  public void testBindInstanceInChildModuleDoesntWork() throws Exception {
    expectedException.expect(CreationException.class);
    Injector parentInjector = Guice.createInjector(new ParentModule());
    A instance = parentInjector.getInstance(A.class);
    assertTrue(instance instanceof AImpl);

    // This fails because A.class already has a binding in the parent module.
    parentInjector.createChildInjector(new ChildModule(instance));
  }

  //===========================================================================================
  // Example 2:  Attempt to bind a class that is annotated with a custom Scope annotation,
  // without binding the scope.  This does not work.
  //===========================================================================================

  private interface B {
    void doB();
  }

  @CustomScoped
  private static class BImpl implements B {
    @Override
    public void doB() {
      // empty
    }
  }

  private static class BModule extends AbstractModule {
    @Override
    protected void configure() {
      bind(B.class).to(BImpl.class);
    }
  }

  @Test
  public void testBindB() throws Exception {
    expectedException.expect(CreationException.class);

    // This fails because B is annotated @CustomScoped but no scope is bound in BModule.
    Guice.createInjector(new BModule());
  }

  //===========================================================================================
  // Example 3:  Custom Scope example.
  //===========================================================================================

  @Target({ TYPE, METHOD }) @Retention(RUNTIME) @ScopeAnnotation
  private @interface CustomScoped { }

  private static class CustomScope implements Scope {
    private final Map<Key<?>, Object> objectCache = new HashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T> Provider<T> scope(Key<T> key, Provider<T> unscoped) {
      return () -> {
        Object object = objectCache.get(key);
        if (null != object) {
          return (T) object;
        }
        object = unscoped.get();
        objectCache.put(key, object);
        return (T) object;
      };
    }
  }

  private static class CustomScopedModule extends AbstractModule {
    private final CustomScope customScope = new CustomScope();

    @Override
    protected void configure() {
      bindScope(CustomScoped.class, customScope);
      bind(B.class).to(BImpl.class);
    }
  }

  @Test
  public void testBindBScoped() throws Exception {
    Injector parentInjector = Guice.createInjector(new ParentModule());
    Injector childInjector = parentInjector.createChildInjector(new CustomScopedModule());
    Injector childInjector2 = parentInjector.createChildInjector(new CustomScopedModule());

    // Each instance of A, from any injector, is unique.
    A a1 = parentInjector.getInstance(A.class);
    A a2 = parentInjector.getInstance(A.class);
    assertTrue(a1 != a2);
    A a3 = childInjector.getInstance(A.class);
    assertTrue(a3 != a2);
    assertTrue(a3 != a1);
    A a4 = childInjector.getInstance(A.class);
    assertTrue(a4 != a3);
    A a5 = childInjector2.getInstance(A.class);
    assertTrue(a5 != a4);
    A a6 = childInjector2.getInstance(A.class);
    assertTrue(a6 != a5);

    // Each child injector will give out only one unique instance of B; that is, B is scoped
    // to the child injector.
    B b1 = childInjector.getInstance(B.class);
    B b2 = childInjector.getInstance(B.class);
    assertTrue(b1 == b2);
    B b3 = childInjector2.getInstance(B.class);
    B b4 = childInjector2.getInstance(B.class);
    assertTrue(b3 != b2);
    assertTrue(b3 == b4);
  }

  //===========================================================================================
  // Example 4:  Shows how bindings are pushed to parent injectors whenever possible.
  // See https://github.com/google/guice/wiki/Bindings.
  // Here CImpl requires no dependencies, so the parent injector binds it via just-in-time
  // binding, even though it is explicitly bound in the child module.  The child module
  // binding is ignored, and the Singleton instance is scoped to the parent injector and all
  // of its descendants.
  //===========================================================================================

  private interface C {
    void doC();
  }

  @Singleton
  private static class CImpl implements C {
    @Override
    public void doC() {
      // empty
    }
  }

  private static class CModule extends AbstractModule {
    @Override
    protected void configure() {
      bind(C.class).to(CImpl.class).in(Singleton.class);
    }
  }

  @Test
  public void testBindCSingleton() throws Exception {
    Injector parentInjector = Guice.createInjector(new ParentModule());
    Injector childInjector = parentInjector.createChildInjector(new CModule());
    Injector childInjector2 = parentInjector.createChildInjector(new CModule());

    // Same singleton instance returned from both child injectors
    C c1 = childInjector.getInstance(C.class);
    C c2 = childInjector.getInstance(C.class);
    assertTrue(c1 == c2);
    C c3 = childInjector2.getInstance(C.class);
    C c4 = childInjector2.getInstance(C.class);
    assertTrue(c3 == c2);
    assertTrue(c3 == c4);

    // Even though the Singleton instance binding is pushed to the parent and scoped to it,
    // you can't get it from the parent injector.
    try {
      parentInjector.getInstance(C.class);
      fail("expected an exception");
    } catch(ConfigurationException e) {
      // empty
    }

    // A new parent injector and its descendants will share a new instance.
    Injector parentInjector2 = Guice.createInjector(new ParentModule());
    Injector childInjector3 = parentInjector2.createChildInjector(new CModule());
    C c6 = childInjector3.getInstance(C.class);
    assertTrue(c6 != c1);
  }

  //===========================================================================================
  // Example 5:  Exactly like Example 4 but without the @Singleton annotation.
  // This time the binding is not pushed to the parent injector, so the child injectors each
  // get their own instance.  I think the way to think of this is:  with the @Singleton
  // annotation and a JIT binding, the parent module can generate a binding for the class
  // that is identical to the child module's binding.  And a tie goes to the parent.
  // Without the @Singleton annotation, the binding generated by the child cannot be pushed
  // to the parent, because it is specific to the child.
  //===========================================================================================

  private interface D {
    void doD();
  }

  private static class DImpl implements D {
    @Override
    public void doD() {
      // empty
    }
  }

  private static class DModule extends AbstractModule {
    @Override
    protected void configure() {
      bind(D.class).to(DImpl.class).in(Singleton.class);
    }
  }

  @Test
  public void testBindDSingleton() throws Exception {
    Injector parentInjector = Guice.createInjector(new ParentModule());
    Injector childInjector = parentInjector.createChildInjector(new DModule());
    Injector childInjector2 = parentInjector.createChildInjector(new DModule());

    D d1 = childInjector.getInstance(D.class);
    D d2 = childInjector.getInstance(D.class);
    assertTrue(d1 == d2);
    D d3 = childInjector2.getInstance(D.class);
    D d4 = childInjector2.getInstance(D.class);
    assertTrue(d3 == d4);
    // This time each child injector gets its own instance.
    assertTrue(d3 != d2);

    // Still can't get an instance from the parent.
    try {
      parentInjector.getInstance(C.class);
      fail("expected an exception");
    } catch(ConfigurationException e) {
      // empty
    }
  }
}

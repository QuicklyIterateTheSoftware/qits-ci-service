package eu.wohlben.qits.ci.control;

/**
 * {@code pact-js}'s pact files, as the JavaScript consumers' pact tests write them: pact v4,
 * the operation on each interaction's {@code qits-call} reference. See {@link PactParityAdapter}.
 */
public final class PactJsParityAdapter implements PactParityAdapter {

  static final String FRAMEWORK = "pact-js";

  @Override
  public String framework() {
    return FRAMEWORK;
  }

  @Override
  public PactFile read(String path, String content) {
    return PactParityAdapter.readPactV4(path, content);
  }
}

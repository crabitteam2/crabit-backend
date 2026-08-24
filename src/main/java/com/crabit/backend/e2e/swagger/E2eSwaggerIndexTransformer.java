package com.crabit.backend.e2e.swagger;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springdoc.webmvc.ui.SwaggerIndexPageTransformer;
import org.springdoc.webmvc.ui.SwaggerIndexTransformer;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.ResourceTransformerChain;
import org.springframework.web.servlet.resource.TransformedResource;

/** Adds the e2e-only persona selector without persisting or rendering synthetic tokens. */
public final class E2eSwaggerIndexTransformer implements SwaggerIndexTransformer {
	private static final String SCRIPT = """

;(async function crabitE2ePersonaSelector() {
  const storageKey = 'crabit.e2e.swagger.persona';
  const scheme = 'SyntheticBearer';
  const clear = () => {
    sessionStorage.removeItem(storageKey);
    if (window.ui) window.ui.logout([scheme]);
  };
  try {
    const response = await fetch('/v3/api-docs/e2e-personas', {cache: 'no-store'});
    if (!response.ok) throw new Error('persona catalog unavailable');
    const personas = await response.json();
    const panel = document.createElement('section');
    panel.id = 'crabit-e2e-persona-selector';
    panel.style.cssText = 'margin:16px auto;padding:16px;max-width:1400px;background:#fff4fa;border:1px solid #fb75bb;border-radius:6px';
    const title = document.createElement('strong');
    title.textContent = 'E2E 테스트 사용자';
    const select = document.createElement('select');
    select.setAttribute('aria-label', 'E2E 테스트 사용자');
    select.append(new Option('인증 안 함', ''));
    personas.forEach(persona => select.append(new Option(persona.label, persona.key)));
    const warning = document.createElement('p');
    warning.textContent = '합성 테스트 자격 증명입니다. 사용자 전환은 공유 픽스처와 변경 데이터를 초기화하지 않습니다.';
    panel.append(title, document.createTextNode(' '), select, warning);
    (document.querySelector('#swagger-ui') || document.body).before(panel);
    const authorize = key => {
      const persona = personas.find(candidate => candidate.key === key);
      if (!persona) { clear(); select.value = ''; return; }
      window.ui.preauthorizeApiKey(scheme, persona.token);
      sessionStorage.setItem(storageKey, persona.key);
    };
    select.addEventListener('change', () => select.value ? authorize(select.value) : clear());
    const saved = sessionStorage.getItem(storageKey);
    if (saved && personas.some(persona => persona.key === saved)) {
      select.value = saved;
      authorize(saved);
    } else clear();
  } catch (ignored) {
    clear();
  }
})();
""";

	private final SwaggerIndexPageTransformer delegate;

	public E2eSwaggerIndexTransformer(SwaggerIndexPageTransformer delegate) {
		this.delegate = delegate;
	}

	@Override
	public Resource transform(HttpServletRequest request, Resource resource, ResourceTransformerChain chain)
			throws IOException {
		Resource transformed = delegate.transform(request, resource, chain);
		if (!"swagger-initializer.js".equals(resource.getFilename())) return transformed;
		String source = transformed.getContentAsString(StandardCharsets.UTF_8);
		return new TransformedResource(transformed, (source + SCRIPT).getBytes(StandardCharsets.UTF_8));
	}
}

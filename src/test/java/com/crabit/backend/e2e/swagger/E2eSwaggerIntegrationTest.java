package com.crabit.backend.e2e.swagger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("e2e")
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:e2e-swagger;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.jpa.hibernate.ddl-auto=none",
	"spring.flyway.enabled=false",
	"crabit.e2e.seed.enabled=false",
	"crabit.documentation.enabled=true",
	"logging.level.root=warn"
})
@AutoConfigureMockMvc
class E2eSwaggerIntegrationTest {
	@Autowired MockMvc mockMvc;
	@TempDir Path temporaryDirectory;

	@Test
	void exposesSixNoStorePersonasAndInjectsAKeyOnlySelector() throws Exception {
		mockMvc.perform(get("/v3/api-docs/e2e-personas"))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
				.andExpect(jsonPath("$.length()").value(6))
				.andExpect(jsonPath("$[0].key").value("owner"))
				.andExpect(jsonPath("$[0].label").value("오너"))
				.andExpect(jsonPath("$[0].token").value("seed-owner-token"));

		String initializer = mockMvc.perform(get("/swagger-ui/swagger-initializer.js"))
				.andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
		assertThat(initializer).contains(
				"E2E 테스트 사용자", "crabit.e2e.swagger.persona", "preauthorizeApiKey",
				"sessionStorage.setItem(storageKey, persona.key)", "window.ui.logout([scheme])")
				.doesNotContain(
						"sessionStorage.setItem(storageKey, persona.token)",
						"/e2e/reset", "resetSeedFixtures", "fixture-reset");
	}

	@Test
	void suppressesStockRawBearerControlsSoThePersonaSelectorIsTheOnlyCredentialControl()
			throws Exception {
		executeInitializer("""
const assert = require('node:assert/strict');
const storage = new Map();
const stockControls = [createStockControl('top-level-authorize'), createStockControl('operation-lock')];
global.testStockControls = stockControls;
global.sessionStorage = {
  getItem: key => storage.has(key) ? storage.get(key) : null,
  setItem: (key, value) => storage.set(key, value),
  removeItem: key => storage.delete(key)
};
installDom(stockControls);
global.fetch = async () => ({ok: true, json: async () => []});
global.window = {ui: {logout: () => {}}};
""", """
await waitFor(() => global.testSelect !== undefined);
assert.equal(global.testSelect.getAttribute('aria-label'), 'E2E 테스트 사용자');
assert.equal(global.testSelect.removed, false);
assert.equal(global.testStockControls.every(control => control.removed), true,
    'stock raw bearer authorization controls must be removed');
assert.equal(global.testCredentialObserver.observedTarget, global.testSwaggerUi);

const lateControl = createStockControl('late-operation-lock');
global.testStockControls.push(lateControl);
global.testCredentialObserver.callback();
assert.equal(lateControl.removed, true,
    'stock authorization controls rendered after initialization must also be removed');
""");
	}

	@Test
	void restoresOnlyTheSavedPersonaKeyAfterTheCatalogAndWaitsForSwaggerUiOnce() throws Exception {
		executeInitializer("""
const assert = require('node:assert/strict');
const events = [];
const storage = new Map([['crabit.e2e.swagger.persona', 'owner']]);
const preauthorizations = [];
let securitySchemeReady = false;
global.sessionStorage = {
  getItem: key => storage.has(key) ? storage.get(key) : null,
  setItem: (key, value) => storage.set(key, value),
  removeItem: key => storage.delete(key)
};
installDom();
global.fetch = async () => {
  events.push('catalog');
  return {ok: true, json: async () => [{key: 'owner', label: '오너', token: 'seed-owner-token'}]};
};
global.window = {
  ui: {
    specSelectors: {
      specJson: () => ({
        getIn: () => securitySchemeReady ? {toJS: () => ({type: 'http', scheme: 'bearer'})} : null
      })
    },
    preauthorizeApiKey: (scheme, token) => {
      preauthorizations.push([scheme, token]);
      if (!securitySchemeReady) return null;
      events.push('authorize');
    },
    logout: () => events.push('logout')
  }
};
""", """
await waitFor(() => global.testSelect !== undefined);
await delay(60);
assert.deepEqual(events, ['catalog']);
assert.equal(preauthorizations.length, 0, 'authorization must wait for SyntheticBearer in the loaded spec');
assert.deepEqual([...storage.entries()], [['crabit.e2e.swagger.persona', 'owner']]);
securitySchemeReady = true;
await waitFor(() => preauthorizations.length === 1);
await delay(80);
assert.deepEqual(events, ['catalog', 'authorize']);
assert.deepEqual(preauthorizations, [['SyntheticBearer', 'seed-owner-token']]);
assert.deepEqual([...storage.entries()], [['crabit.e2e.swagger.persona', 'owner']]);
assert.equal(global.testSelect.value, 'owner');
""");
	}

	@Test
	void waitsForSwaggerUiWhenTheCurrentPersonaIsSelectedBeforeUiReadiness() throws Exception {
		executeInitializer("""
const assert = require('node:assert/strict');
const storage = new Map();
const preauthorizations = [];
global.sessionStorage = {
  getItem: key => storage.has(key) ? storage.get(key) : null,
  setItem: (key, value) => storage.set(key, value),
  removeItem: key => storage.delete(key)
};
installDom();
global.fetch = async () => ({
  ok: true,
  json: async () => [{key: 'owner', label: '오너', token: 'seed-owner-token'}]
});
global.window = {};
""", """
await waitFor(() => global.testSelect !== undefined);
global.testSelect.value = 'owner';
global.testSelect.listeners.change();
await delay(60);
assert.equal(preauthorizations.length, 0, 'current selection must wait for Swagger UI readiness');
window.ui = {
  specSelectors: {
    specJson: () => ({components: {securitySchemes: {SyntheticBearer: {type: 'http'}}}})
  },
  preauthorizeApiKey: (scheme, token) => {
    preauthorizations.push([scheme, token]);
  },
  logout: () => {}
};
await waitFor(() => preauthorizations.length === 1);
await delay(80);
assert.deepEqual(preauthorizations, [['SyntheticBearer', 'seed-owner-token']]);
assert.deepEqual([...storage.entries()], [['crabit.e2e.swagger.persona', 'owner']]);
assert.equal(global.testSelect.value, 'owner');
""");
	}

	@Test
	void clearsAStaleSavedPersonaAndVisiblySelectsNoAuthentication() throws Exception {
		executeInitializer("""
const assert = require('node:assert/strict');
const storage = new Map([['crabit.e2e.swagger.persona', 'retired-persona']]);
const calls = [];
global.sessionStorage = {
  getItem: key => storage.has(key) ? storage.get(key) : null,
  setItem: (key, value) => storage.set(key, value),
  removeItem: key => storage.delete(key)
};
installDom();
global.fetch = async () => ({
  ok: true,
  json: async () => [{key: 'owner', label: '오너', token: 'seed-owner-token'}]
});
global.window = {
  ui: {
    preauthorizeApiKey: (...args) => calls.push(['authorize', ...args]),
    logout: schemes => calls.push(['logout', schemes])
  }
};
""", """
await waitFor(() => global.testSelect !== undefined && calls.length > 0);
assert.deepEqual([...storage.entries()], []);
assert.equal(global.testSelect.value, '');
assert.deepEqual(calls, [['logout', ['SyntheticBearer']]]);
""");
	}

	@Test
	void clearsTheSavedPersonaAndVisibleSelectionWhenPreauthorizationFails() throws Exception {
		executeInitializer("""
const assert = require('node:assert/strict');
const storage = new Map([['crabit.e2e.swagger.persona', 'owner']]);
const calls = [];
global.sessionStorage = {
  getItem: key => storage.has(key) ? storage.get(key) : null,
  setItem: (key, value) => storage.set(key, value),
  removeItem: key => storage.delete(key)
};
installDom();
global.fetch = async () => ({
  ok: true,
  json: async () => [{key: 'owner', label: '오너', token: 'seed-owner-token'}]
});
global.window = {
  ui: {
    specSelectors: {
      specJson: () => ({components: {securitySchemes: {SyntheticBearer: {type: 'http'}}}})
    },
    preauthorizeApiKey: () => { calls.push('authorize'); throw new Error('rejected'); },
    logout: () => calls.push('logout')
  }
};
""", """
await waitFor(() => calls.includes('logout'));
assert.deepEqual([...storage.entries()], []);
assert.equal(global.testSelect.value, '');
assert.deepEqual(calls, ['authorize', 'logout']);
""");
	}

	private void executeInitializer(String setup, String assertions) throws Exception {
		String initializer = mockMvc.perform(get("/swagger-ui/swagger-initializer.js"))
				.andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
		Path script = temporaryDirectory.resolve("swagger-initializer-behavior.js");
		Files.writeString(script, nodeHarness(setup, initializer, assertions), StandardCharsets.UTF_8);

		Process process = new ProcessBuilder("node", script.toString())
				.redirectErrorStream(true)
				.start();
		boolean completed = process.waitFor(10, TimeUnit.SECONDS);
		if (!completed) process.destroyForcibly();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(completed).as("Node behavior harness completed: %s", output).isTrue();
		assertThat(process.exitValue()).as("Node behavior harness output: %s", output).isZero();
	}

	private static String nodeHarness(String setup, String initializer, String assertions)
			throws IOException {
		return """
'use strict';
const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const waitFor = async condition => {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (condition()) return;
    await delay(10);
  }
  throw new Error('condition was not satisfied');
};
const createStockControl = name => ({
  name,
  removed: false,
  remove() { this.removed = true; }
});
const installDom = (stockControls = []) => {
  class Element {
    constructor(tagName) {
      this.tagName = tagName;
      this.children = [];
      this.listeners = {};
      this.style = {};
      this.value = '';
      this.attributes = {};
      this.removed = false;
    }
    append(...children) { this.children.push(...children); }
    remove() { this.removed = true; }
    setAttribute(name, value) { this.attributes[name] = value; }
    getAttribute(name) { return this.attributes[name]; }
    addEventListener(name, listener) { this.listeners[name] = listener; }
  }
  const swaggerUi = {before() {}};
  global.testSwaggerUi = swaggerUi;
  global.MutationObserver = class MutationObserver {
    constructor(callback) { this.callback = callback; global.testCredentialObserver = this; }
    observe(target, options) { this.observedTarget = target; this.options = options; }
  };
  global.Option = function Option(label, value) { return {label, value}; };
  global.document = {
    body: swaggerUi,
    createElement: tagName => {
      const element = new Element(tagName);
      if (tagName === 'select') global.testSelect = element;
      return element;
    },
    createTextNode: textContent => ({textContent}),
    querySelector: selector => selector === '#swagger-ui' ? swaggerUi : null,
    querySelectorAll: selector => selector === '#swagger-ui .auth-wrapper, #swagger-ui .authorization__btn'
      ? stockControls.filter(control => !control.removed)
      : []
  };
};
""" + setup + System.lineSeparator() + initializer + """

;(async () => {
""" + assertions + """
})().then(() => process.exit(0)).catch(error => {
  console.error(error && error.stack ? error.stack : error);
  process.exit(1);
});
""";
	}
}

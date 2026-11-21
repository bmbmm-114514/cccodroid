import "../gw/config.mjs";
import { test } from "node:test";
import assert from "node:assert/strict";
import { Readable } from "node:stream";
import { routeModels, guessModelsUrls, fetchModels } from "../gw/models.mjs";
import { providerById, routeProviders, modelsUrlOk } from "../gw/providers.mjs";
import { routeMulti } from "../gw/multi.mjs";

async function call(route, url, data) {
  const path = new URL(url, "http://gateway.invalid");
  const req = Readable.from(data ? [Buffer.from(JSON.stringify(data))] : []);
  req.method = data ? "POST" : "GET";
  const result = {};
  await route(req, {
    writeHead(status) { result.status = status; },
    end(body) { result.body = JSON.parse(body); },
  }, path, path.pathname);
  return result;
}

const save = async (data) => {
  const result = await call(routeProviders, "/api/providers/save", data);
  assert.equal(result.status, 200);
  return result.body;
};
const native = (provider, refresh = false) => call(routeMulti,
  "/api/multi/models?agent=claude&provider=" + provider + (refresh ? "&refresh=1" : ""));
const classic = (provider, refresh = false) => call(routeModels,
  "/api/models?" + (refresh ? "browse=" + provider + "&refresh=1" : "provider=" + provider));
const models = (...ids) => ({data: ids.map(id => ({id, context_window: 32768}))});

test("save a custom relay, discover by root or /v1, refresh, and select the returned model from either picker", async t => {
  const base = "http://relay-fixture.invalid:8123";
  let response = models("vendor/chat-a", "vendor/chat-b");
  const upstream = t.mock.method(globalThis, "fetch", async (url, options) => {
    assert.equal(new URL(url).href, base + "/v1/models");
    assert.equal(options.headers.Authorization, "Bearer fixture-a");
    assert.equal(options.redirect, "manual");
    return Response.json(response);
  });
  for (const suffix of ["", "/v1/"]) {
    response = models("vendor/chat-a", "vendor/chat-b");
    const {id, provider, plainHttp} = await save({label:"relay", baseUrl:base + suffix, apiKey:"fixture-a"});
    assert.equal(provider.baseUrl, base);
    assert.equal(provider.defaultModel, null);
    assert.equal(plainHttp, true);
    const before = upstream.mock.callCount();
    assert.deepEqual((await native(id)).body.models.map(m => m.id), ["vendor/chat-a", "vendor/chat-b"]);
    const menu = await classic(id);
    assert.equal(menu.body.listProvider, id);
    assert.deepEqual(menu.body.models.map(m => m.id), ["vendor/chat-a", "vendor/chat-b"]);
    assert.equal(upstream.mock.callCount(), before + 1);
    response = models("vendor/chat-b", "vendor/chat-c");
    const updated = await classic(id, true);
    assert.deepEqual(updated.body.models.map(m => m.id), ["vendor/chat-b", "vendor/chat-c"]);
    const choice = await call(routeMulti, "/api/multi/preferences", {agent:"claude", provider:id, model:"vendor/chat-c"});
    assert.equal(choice.body.model, "vendor/chat-c");
    assert.equal((await call(routeMulti, "/api/multi/preferences?provider=" + id)).body.model, "vendor/chat-c");
    assert.equal((await save({id, defaultModel:"vendor/chat-c", apiKey:""})).provider.hasKey, true);
    const picked = await native(id, true);
    assert.equal(picked.body.defaultModel, "vendor/chat-c");
    assert.equal(picked.body.models[1].label, "vendor/chat-c");
    assert.equal(upstream.mock.callCount(), before + 3);
  }
});

test("refresh distinguishes empty, malformed, and denied catalogs while ordinary requests retain valid choices", async t => {
  const base = "https://proxy-fixture.invalid/team";
  let payload = models("first"), status = 200;
  const headers = [];
  t.mock.method(globalThis, "fetch", async (url, opts) => {
    const candidate = new URL(url);
    assert.equal(candidate.origin, new URL(base).origin);
    if (candidate.pathname !== "/team/v1/models") return Response.json({}, {status:404});
    headers.push(opts.headers.Authorization);
    return Response.json(payload, {status});
  });
  const {id} = await save({label:"rotating", baseUrl:base, apiKey:"fixture-first"});
  const earlier = providerById(id);
  assert.deepEqual((await native(id, true)).body.models.map(m => m.id), ["first"]);
  assert.deepEqual(headers, ["Bearer fixture-first"]);
  for (const denied of [401, 403, 429]) {
    status = denied;
    payload = {message:"fixture-first"};
    const r = await classic(id, true);
    assert.equal(r.status, 502);
    assert.equal(r.body.error, "model_catalog_unavailable");
    assert.match(r.body.message, new RegExp(String(denied)));
    assert.doesNotMatch(r.body.message, /fixture-first/);
    assert.deepEqual((await native(id)).body.models.map(m => m.id), ["first"]);
  }
  status = 200;
  await save({id, apiKey:"fixture-second"});
  payload = models("old-snapshot");
  await fetchModels(false, earlier, {guess:true});
  assert.equal(headers.at(-1), "Bearer fixture-first");
  payload = models("second");
  assert.deepEqual((await native(id)).body.models.map(m => m.id), ["second"]);
  assert.equal(headers.at(-1), "Bearer fixture-second");
  payload = {error:{message:"fixture-second"}, data:[]};
  const malformed = await native(id, true);
  assert.equal(malformed.status, 502);
  assert.equal(malformed.body.error, "model_catalog_unavailable");
  assert.doesNotMatch(malformed.body.message, /fixture-second/);
  assert.deepEqual((await classic(id)).body.models.map(m => m.id), ["second"]);
  payload = models();
  const empty = await native(id, true);
  assert.equal(empty.status, 200);
  assert.deepEqual(empty.body.models, []);
});

test("the saved HTTP base exception does not apply to explicit catalog URLs, unsaved providers, or cross-origin redirects", async t => {
  const base = "http://http-fixture.invalid:8080";
  const seen = [];
  t.mock.method(globalThis, "fetch", async url => {
    seen.push(String(url));
    return new Response(null, {status:302, headers:{location:"http://other-fixture.invalid/list"}});
  });
  const {id} = await save({baseUrl:base, apiKey:"fixture-key"});
  assert.equal((await native(id, true)).status, 502);
  assert.ok(seen.length > 0);
  assert.ok(seen.every(u => guessModelsUrls(base).includes(u)));
  seen.length = 0;
  for (const modelsUrl of [base + "/v1/models", "http://other-fixture.invalid/models", "https://other-fixture.invalid/models"]) {
    assert.equal(modelsUrlOk({baseUrl:base, modelsUrl}), false);
    await save({id, modelsUrl, apiKey:"fixture-key"});
    assert.equal((await classic(id, true)).status, 400);
  }
  const unsaved = {...providerById(id), id:"custom-unsaved-fixture", modelsUrl:"", explicit:true};
  await assert.rejects(fetchModels(true, unsaved, {guess:true, reportErrors:true}), {status:400});
  await save({id, clearKey:true, modelsUrl:""});
  assert.deepEqual(await fetchModels(false, providerById(id)), []); // pre-run fallback must not mask an unconfigured key
  assert.match((await native(id)).body.message, /API Key/);
  assert.deepEqual(seen, []);
});

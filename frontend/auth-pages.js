"use strict";

/**
 * Page logic for login.html, register.html and profile.html (Stage 6A). All API access goes through
 * `window.FolixAuth` (auth.js). Everything user-controlled is written with textContent — never innerHTML.
 */
(function () {
  const $ = (id) => document.getElementById(id);
  const params = new URLSearchParams(window.location.search);
  const next = window.FolixAuth.safeNext(params.get("next"));

  function show(el, text) {
    el.textContent = text;
    el.hidden = false;
  }
  function hide(el) {
    el.textContent = "";
    el.hidden = true;
  }

  /** Clears then fills per-field error messages. `map` = { fieldName: [inputEl, errorEl] }. */
  function applyFieldErrors(map, fields) {
    Object.keys(map).forEach((name) => {
      const [input, errEl] = map[name];
      const msg = fields && typeof fields[name] === "string" ? fields[name] : "";
      if (msg) {
        show(errEl, msg);
        input.setAttribute("aria-invalid", "true");
        input.setAttribute("aria-describedby", errEl.id);
      } else {
        hide(errEl);
        input.removeAttribute("aria-invalid");
        input.removeAttribute("aria-describedby");
      }
    });
  }

  /** Where to go after logging in. "close" is the Scanner's "log in in a new tab" flow: stay put and say so. */
  function afterLogin(farmer) {
    if (next === "close") {
      $("loginCard").hidden = true;
      $("loginDone").hidden = false;
      $("loginDoneTitle").textContent = "You're logged in";
      $("loginDoneText").textContent = "You can close this tab and go back to your scan — then press Try Again to save it.";
      $("loginDoneLink").hidden = true;
      return;
    }
    window.location.href = next;
  }

  // ---------------------------------------------------------------
  // login.html
  // ---------------------------------------------------------------

  function initLogin() {
    const form = $("loginForm");
    const email = $("loginEmail");
    const password = $("loginPassword");
    const submit = $("loginSubmit");
    const errors = { email: [email, $("loginEmailError")], password: [password, $("loginPasswordError")] };

    // Keep the destination when hopping between login and register.
    if (params.has("next")) {
      $("registerLink").href = "register.html?next=" + encodeURIComponent(params.get("next"));
    }

    // Already logged in? Don't show the form again.
    window.FolixAuth.getSession().then((s) => {
      if (s.authenticated) {
        $("loginCard").hidden = true;
        $("loginDone").hidden = false;
        $("loginDoneText").textContent = "Signed in as " + s.farmer.name + ".";
        if (next === "close") $("loginDoneLink").hidden = true;
        else $("loginDoneLink").href = next;
      }
    });

    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      hide($("loginError"));

      const fields = {};
      if (!email.value.trim()) fields.email = "Enter your email address.";
      if (!password.value) fields.password = "Enter your password.";
      applyFieldErrors(errors, fields);
      if (Object.keys(fields).length) return;

      submit.disabled = true;
      submit.textContent = "Logging in…";
      try {
        const farmer = await window.FolixAuth.login(email.value.trim(), password.value);
        password.value = "";
        afterLogin(farmer);
      } catch (err) {
        if (err.fields && Object.keys(err.fields).length) applyFieldErrors(errors, err.fields);
        else show($("loginError"), err.message || "Couldn't log in. Please try again.");
      } finally {
        submit.disabled = false;
        submit.textContent = "Log in";
      }
    });
  }

  // ---------------------------------------------------------------
  // register.html
  // ---------------------------------------------------------------

  function initRegister() {
    const form = $("registerForm");
    const name = $("regName");
    const email = $("regEmail");
    const password = $("regPassword");
    const confirm = $("regConfirm");
    const submit = $("registerSubmit");
    const errors = {
      name: [name, $("regNameError")],
      email: [email, $("regEmailError")],
      password: [password, $("regPasswordError")],
      confirm: [confirm, $("regConfirmError")],
    };

    if (params.has("next")) {
      $("loginLink").href = "login.html?next=" + encodeURIComponent(params.get("next"));
    }

    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      hide($("registerError"));

      // Quick checks for a friendlier form; the server re-validates everything and is the authority.
      const fields = {};
      if (!name.value.trim()) fields.name = "Enter your name.";
      if (!email.value.trim()) fields.email = "Enter your email address.";
      if (password.value.length < 8) fields.password = "Password must be at least 8 characters.";
      if (confirm.value !== password.value) fields.confirm = "The passwords don't match.";
      applyFieldErrors(errors, fields);
      if (Object.keys(fields).length) return;

      submit.disabled = true;
      submit.textContent = "Creating account…";
      let created = false;
      try {
        await window.FolixAuth.register(name.value, email.value, password.value);
        created = true;
        // Registering does not log you in on the server; do that now with the same credentials.
        const farmer = await window.FolixAuth.login(email.value.trim(), password.value);
        password.value = "";
        confirm.value = "";
        afterLogin(farmer);
      } catch (err) {
        if (created) {
          show($("registerError"), "Your account was created, but we couldn't log you in automatically. Please log in.");
          window.setTimeout(() => { window.location.href = "login.html?next=" + encodeURIComponent(next); }, 1800);
        } else if (err.fields && Object.keys(err.fields).length) {
          applyFieldErrors(errors, err.fields);
        } else {
          show($("registerError"), err.message || "Couldn't create your account. Please try again.");
        }
      } finally {
        submit.disabled = false;
        submit.textContent = "Create account";
      }
    });
  }

  // ---------------------------------------------------------------
  // profile.html
  // ---------------------------------------------------------------

  function initProfile() {
    const panels = ["profileLoading", "profileAuthRequired", "profileError", "profileCard"];
    const showPanel = (id) => panels.forEach((p) => { $(p).hidden = p !== id; });

    function formatDate(iso) {
      const d = new Date(iso);
      return isNaN(d.getTime()) ? "—" : d.toLocaleDateString(undefined, { year: "numeric", month: "long", day: "numeric" });
    }

    async function load() {
      showPanel("profileLoading");
      try {
        const p = await window.FolixAuth.getProfile();
        $("profileName").textContent = p.name;
        $("profileEmail").textContent = p.email;
        $("profileSince").textContent = formatDate(p.createdAt);
        showPanel("profileCard");
      } catch (err) {
        if (err.isUnauthenticated) {
          showPanel("profileAuthRequired");
        } else {
          $("profileErrorText").textContent = err.message || "Something went wrong while reaching the FOLIX server.";
          showPanel("profileError");
        }
      }
    }

    $("profileRetry").addEventListener("click", load);
    $("profileLogout").addEventListener("click", async () => {
      const btn = $("profileLogout");
      btn.disabled = true;
      try {
        await window.FolixAuth.logout();
        window.location.href = "login.html";
      } catch (err) {
        btn.disabled = false;
        $("profileErrorText").textContent = err.message || "Couldn't log out.";
        showPanel("profileError");
      }
    });
    load();
  }

  document.addEventListener("DOMContentLoaded", () => {
    const page = document.body.dataset.page;
    if (page === "login") initLogin();
    else if (page === "register") initRegister();
    else if (page === "profile") initProfile();
  });
})();

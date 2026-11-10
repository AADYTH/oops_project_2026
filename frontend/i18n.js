"use strict";

/**
 * Multi-language + voice (Tier 2 upgrade #6). Two things live here:
 *
 * 1. A small i18n dictionary applied via [data-i18n] attributes in
 *    index.html. Deliberately scoped to interactive UI strings (nav,
 *    buttons, section headers) rather than every paragraph of marketing
 *    copy — those longer passages need a professional/native-speaker
 *    translation review before shipping to real farmers (a bad machine
 *    translation of agronomy guidance is worse than English-only), so
 *    they're left as a follow-up rather than auto-translated here.
 *    Malayalam is included because that's most useful for users in Kerala;
 *    add more languages by extending DICTIONARIES.
 *
 * 2. Voice input/output via the browser's Web Speech API — genuinely
 *    useful for low-literacy accessibility, and needs no server or API
 *    key. Voice INPUT here is used for spoken symptom notes (kept as
 *    on-screen, human-readable text — the backend has no free-text symptom
 *    field yet, so this is an accessibility aid for the user's own
 *    reference, not something that changes the diagnosis). Voice OUTPUT
 *    reads a diagnosis result aloud in the selected language.
 */
const CropGuardI18n = (() => {
  const DICTIONARIES = {
    en: {
      "nav.home": "Home", "nav.network": "Agricultural Network",
      "nav.scan": "Scanner", "nav.how": "How it works", "nav.history": "History",
      "nav.weather": "Weather risk", "nav.heatmap": "Disease Map", "nav.dashboard": "Dashboard",
      "nav.about": "About the model",
      "scan.submit": "Diagnose this photo", "scan.voice": "Describe by voice instead",
      "scan.note": "FOLIX gives guidance to support a decision, not a substitute for an agronomist's on-site inspection.",
      "weather.title": "Weather-based disease risk",
      "weather.sub": "Uses your shared location and the 5-day forecast to flag conditions that favor common crop diseases.",
      "weather.empty": "Share your location above to see a risk forecast for your fields.",
      "reminders.title": "Treatment reminders",
      "reminders.sub": "Reapplication and pre-harvest-interval dates generated from your scan results this session.",
      "reminders.empty": "No reminders yet — they appear here after a scan recommends a treatment.",
      "result.uncertain": "This result is uncertain",
      "result.uncertainBody": "Model confidence is low. Treat this as one possibility, not a confirmed diagnosis.",
      "result.alternatives": "Other possibilities the model considered",
      "result.partUnsupported": "This photo type isn't reliably supported yet",
      "feedback.prompt": "Was this diagnosis correct?",
      "feedback.yes": "Yes, correct", "feedback.no": "No, incorrect",
      "feedback.thanks": "Thanks — recorded."
    },
    hi: {
      "nav.home": "होम", "nav.network": "कृषि नेटवर्क",
      "nav.scan": "स्कैन", "nav.how": "यह कैसे काम करता है", "nav.history": "इतिहास",
      "nav.weather": "मौसम जोखिम", "nav.heatmap": "प्रकोप मानचित्र", "nav.dashboard": "डैशबोर्ड",
      "nav.about": "मॉडल के बारे में",
      "scan.submit": "इस फोटो का निदान करें", "scan.voice": "इसके बजाय आवाज़ से बताएं",
      "scan.note": "FOLIX एक सहायक मार्गदर्शन है, कृषि विशेषज्ञ के निरीक्षण का विकल्प नहीं है।",
      "weather.title": "मौसम आधारित रोग जोखिम",
      "weather.sub": "आपके स्थान और पूर्वानुमान का उपयोग करके फसल रोगों के अनुकूल मौसम की चेतावनी देता है।",
      "weather.empty": "अपने खेतों के लिए जोखिम पूर्वानुमान देखने हेतु ऊपर अपना स्थान साझा करें।",
      "reminders.title": "उपचार अनुस्मारक",
      "reminders.sub": "इस सत्र के स्कैन परिणामों से बनाई गई पुनः-प्रयोग और कटाई-पूर्व अंतराल तिथियां।",
      "reminders.empty": "अभी तक कोई अनुस्मारक नहीं है।",
      "result.uncertain": "यह परिणाम अनिश्चित है",
      "result.uncertainBody": "मॉडल का विश्वास स्तर कम है। इसे एक पुष्ट निदान न मानें।",
      "result.alternatives": "मॉडल द्वारा विचार की गई अन्य संभावनाएं",
      "result.partUnsupported": "इस प्रकार की फोटो के लिए विश्वसनीय पहचान अभी उपलब्ध नहीं है",
      "feedback.prompt": "क्या यह निदान सही था?",
      "feedback.yes": "हां, सही", "feedback.no": "नहीं, गलत",
      "feedback.thanks": "धन्यवाद — दर्ज कर लिया गया।"
    },
    ml: {
      "nav.home": "ഹോം", "nav.network": "കാർഷിക ശൃംഖല",
      "nav.scan": "സ്കാൻ", "nav.how": "ഇത് എങ്ങനെ പ്രവർത്തിക്കുന്നു", "nav.history": "ചരിത്രം",
      "nav.weather": "കാലാവസ്ഥാ അപകടസാധ്യത", "nav.heatmap": "രോഗവ്യാപന ഭൂപടം", "nav.dashboard": "ഡാഷ്ബോർഡ്",
      "nav.about": "മോഡലിനെക്കുറിച്ച്",
      "scan.submit": "ഈ ഫോട്ടോ പരിശോധിക്കുക", "scan.voice": "പകരം ശബ്ദത്തിലൂടെ പറയുക",
      "scan.note": "FOLIX ഒരു സഹായക മാർഗ്ഗനിർദ്ദേശമാണ്, വിദഗ്ധ പരിശോധനയ്ക്ക് പകരമല്ല.",
      "weather.title": "കാലാവസ്ഥ അടിസ്ഥാനമാക്കിയുള്ള രോഗസാധ്യത",
      "weather.sub": "നിങ്ങളുടെ സ്ഥാനവും പ്രവചനവും ഉപയോഗിച്ച് രോഗസാധ്യതയുള്ള കാലാവസ്ഥ മുൻകൂട്ടി കാണിക്കുന്നു.",
      "weather.empty": "അപകടസാധ്യതാ പ്രവചനം കാണാൻ മുകളിൽ നിങ്ങളുടെ സ്ഥാനം പങ്കിടുക.",
      "reminders.title": "ചികിത്സാ ഓർമ്മപ്പെടുത്തലുകൾ",
      "reminders.sub": "ഈ സെഷനിലെ സ്കാൻ ഫലങ്ങളിൽ നിന്ന് സൃഷ്ടിച്ച തീയതികൾ.",
      "reminders.empty": "ഇതുവരെ ഓർമ്മപ്പെടുത്തലുകൾ ഇല്ല.",
      "result.uncertain": "ഈ ഫലം അനിശ്ചിതത്വത്തിലാണ്",
      "result.uncertainBody": "മോഡലിന്റെ വിശ്വാസ്യത കുറവാണ്. ഇത് സ്ഥിരീകരിച്ചതായി കണക്കാക്കരുത്.",
      "result.alternatives": "മോഡൽ പരിഗണിച്ച മറ്റ് സാധ്യതകൾ",
      "result.partUnsupported": "ഈ തരം ഫോട്ടോയ്ക്ക് ഇതുവരെ വിശ്വസനീയമായ തിരിച്ചറിയൽ ലഭ്യമല്ല",
      "feedback.prompt": "ഈ രോഗനിർണയം ശരിയായിരുന്നോ?",
      "feedback.yes": "അതെ, ശരി", "feedback.no": "ഇല്ല, തെറ്റ്",
      "feedback.thanks": "നന്ദി — രേഖപ്പെടുത്തി."
    }
  };

  const SPEECH_LANG_CODES = { en: "en-US", hi: "hi-IN", ml: "ml-IN" };

  let currentLang = localStorage.getItem("cropguard-lang") || "en";

  function t(key) {
    return (DICTIONARIES[currentLang] && DICTIONARIES[currentLang][key]) || DICTIONARIES.en[key] || key;
  }

  function apply() {
    document.querySelectorAll("[data-i18n]").forEach((el) => {
      el.textContent = t(el.dataset.i18n);
    });
    document.documentElement.lang = currentLang;
  }

  function setLanguage(lang) {
    if (!DICTIONARIES[lang]) return;
    currentLang = lang;
    localStorage.setItem("cropguard-lang", lang);
    apply();
  }

  function getLanguage() {
    return currentLang;
  }

  function speak(text) {
    if (!("speechSynthesis" in window) || !text) return;
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = SPEECH_LANG_CODES[currentLang] || "en-US";
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(utterance);
  }

  function listenOnce() {
    return new Promise((resolve, reject) => {
      const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
      if (!SpeechRecognition) {
        reject(new Error("Voice input isn't supported in this browser."));
        return;
      }
      const recognition = new SpeechRecognition();
      recognition.lang = SPEECH_LANG_CODES[currentLang] || "en-US";
      recognition.interimResults = false;
      recognition.maxAlternatives = 1;
      recognition.onresult = (event) => resolve(event.results[0][0].transcript);
      recognition.onerror = (event) => reject(new Error(event.error || "Voice recognition error"));
      recognition.onnomatch = () => reject(new Error("Could not understand the speech."));
      recognition.start();
    });
  }

  document.addEventListener("DOMContentLoaded", () => {
    apply();
    const select = document.getElementById("languageSelect");
    if (select) {
      select.value = currentLang;
      select.addEventListener("change", (e) => setLanguage(e.target.value));
    }
  });

  return { t, apply, setLanguage, getLanguage, speak, listenOnce };
})();

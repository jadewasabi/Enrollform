// ===== EDIT YOUR SCHOOL DETAILS HERE (they update on every page) =====
var SCHOOL = {
  name:    "School Name",
  tagline: "Learning today, leading tomorrow",
  address: "Street, Barangay, City, Province",
  tel:     "(000) 000-0000",
  mobile:  "0900 000 0000",
  email:   "youradmin@gmail.com"
};
// Address of the enrollment system (the Java app). Change this when you deploy.
var APP_URL = "http://localhost:8080/";
// =====================================================================

document.querySelectorAll("[data-fill]").forEach(function (el) {
  var v = SCHOOL[el.dataset.fill];
  if (v) el.textContent = v;
});
document.querySelectorAll("a.js-app").forEach(function (a) { a.href = APP_URL; });
document.querySelectorAll("a[data-tel]").forEach(function (a) { a.href = "tel:" + SCHOOL[a.dataset.tel].replace(/[^+\d]/g, ""); });
document.querySelectorAll("a[data-mail]").forEach(function (a) { a.href = "mailto:" + SCHOOL.email; });
document.getElementById("yr").textContent = new Date().getFullYear();

// Photos: if an image file is missing, hide it so the placeholder label shows.
document.querySelectorAll(".ph img").forEach(function (img) {
  function miss() { img.style.display = "none"; }
  if (img.complete && img.naturalWidth === 0) miss();
  img.addEventListener("error", miss);
});

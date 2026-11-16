/* =====================================================
   IMAGES.JS  -  the ONLY file you need to edit for photos
   -----------------------------------------------------
   1. Put your photos in the "images" folder (or anywhere).
   2. Type the file name between the quotes below.
      Path is relative to index.html, e.g. "images/stem.jpg"
   3. Leave "" (empty) to keep the default colored design.
   Save, then refresh the page (Ctrl+Shift+R).
   Tip: file names are case-sensitive: bg1.jpg is not BG1.JPG
   ===================================================== */
const SITE_IMAGES = {

  // Big banner at the top of the home page
  hero: "bg2.jpg",
  heroDarkness: 0.5,        // 0 = no dark layer, 1 = very dark (keeps white text readable)

  // Small picture in the "Now accepting enrollees" tag
  tag: "",

  // Photo for each strand card on the home page
  strands: {
    STEM:  "",
    ABM:   "",
    HUMSS: "",
    GAS:   "",
    TVL:   ""
  },

  // "Life on Campus" infinite carousel.
  // Add or remove lines freely. Each line = one photo.
  campus: [
    { src: "Intrams.jpg", caption: "Intramurals" },
    { src: "tourn.jpg", caption: "Tournaments" },
    { src: "spt.jpg", caption: "Sports" },
    { src: "alumni.jpg", caption: "Alumni Wall" },
    { src: "club.png", caption: "Clubs" },
    { src: "main.jpg", caption: "Main Campus" },
    { src: "bg1.jpg", caption: "Annex Campus" },
    { src: "tc.png", caption: "Teachers" }
  ],

  // About page
  principal: "",            
  videoPoster: "bg2.jpg",         
  videoFile: "abt.mp4"            
};

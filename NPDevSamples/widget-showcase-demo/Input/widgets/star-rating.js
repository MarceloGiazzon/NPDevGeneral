window.NpdevCustomWidgets.register("widgets/star-rating.js", {
  render: function (field, value) {
    var wrapper = document.createElement("div");
    wrapper.className = "star-rating-widget";
    wrapper.style.display = "inline-flex";
    wrapper.style.gap = "2px";

    var hidden = document.createElement("input");
    hidden.type = "hidden";
    hidden.name = field.name;
    var current = parseInt(value, 10);
    if (isNaN(current)) { current = 0; }
    hidden.value = String(current);
    wrapper.appendChild(hidden);

    var stars = [];
    function repaint() {
      stars.forEach(function (star, index) {
        star.textContent = (index < current) ? "★" : "☆";
      });
    }

    for (var i = 0; i < 5; i++) {
      (function (starIndex) {
        var star = document.createElement("span");
        star.textContent = "☆";
        star.style.cursor = "pointer";
        star.style.fontSize = "20px";
        star.addEventListener("click", function () {
          current = starIndex + 1;
          hidden.value = String(current);
          repaint();
          hidden.dispatchEvent(new Event("input", { bubbles: true }));
          hidden.dispatchEvent(new Event("change", { bubbles: true }));
        });
        stars.push(star);
        wrapper.appendChild(star);
      })(i);
    }
    repaint();

    return wrapper;
  }
});

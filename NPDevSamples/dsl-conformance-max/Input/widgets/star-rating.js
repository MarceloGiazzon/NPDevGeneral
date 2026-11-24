// REG-357 conformance widget: the smallest field widget (presentationMetadata.widget "custom" + customWidgetRef).
// It renders a plain number input named after the field; nothing is fetched and nothing is stored.
window.NpdevCustomWidgets.register("widgets/star-rating.js", {
  render: function (field, value) {
    var input = document.createElement("input");
    input.name = field.name;
    input.type = "number";
    input.min = "0";
    input.max = "5";
    input.value = value || "";
    return input;
  }
});

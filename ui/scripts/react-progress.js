
function merge(a, b) {
  var object = {};
  Object.keys(a).forEach(function(key) { object[key] = a[key]; });
  Object.keys(b).forEach(function(key) { object[key] = b[key]; });
  return object;
}

var Progress = React.createClass({
  displayName: 'Progress',

  getDefaultProps: function() {
    return {
      height: 2,
      percent: 0,
      speed: .4,
      style: {}
    };
  },

  render: function () {
    var progressStyle = merge({
      display: 'inline-block',
      position: 'relative',
      width: (this.props.percent + "%"),
      maxWidth: '100% !important',
      height: (this.props.height + "px"),
      boxShadow: '1px 1px 1px rgba(0,0,0,0.4)',
      borderRadius: '0 1px 1px 0',
      WebkitTransition: (this.props.speed + "s width, " + this.props.speed + "s background-color"),
      transition: (this.props.speed + "s width, " + this.props.speed + "s background-color")
    }, this.props.style);

    if (this.props.color && this.props.color !== 'rainbow') {
      progressStyle.backgroundColor = this.props.style.backgroundColor || this.props.color;
    } else {
      progressStyle.backgroundImage = this.props.style.backgroundImage || 'linear-gradient(to right, #4cd964, #5ac8fa, #007aff, #34aadc, #5856d6, #FF2D55)';
      progressStyle.backgroundSize = this.props.style.backgroundSize || ("100vw " + this.props.height + "px");
    }

    return React.createElement("div", {className: "progress", style: progressStyle});
  }
});
#!/usr/bin/env python3

import os
from os.path import dirname, exists, join

prefix = """
<?xml version="1.0"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN"
    "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
<head>
<meta name="scm" content="mercurial" />
<meta charset="utf-8">
<meta http-equiv="cache-control" content="no-cache" />
<meta http-equiv="Pragma" content="no-cache" />
<meta http-equiv="Expires" content="-1" />
<!--
   Note to customizers: the body of the webrev is IDed as SUNWwebrev
   to allow easy overriding by users of webrev via the userContent.css
   mechanism available in some browsers.

   For example, to have all "removed" information be red instead of
   brown, set a rule in your userContent.css file like:

       body#SUNWwebrev span.removed { color: red ! important; }
-->
<style type="text/css" media="screen">
body {
    background-color: #eeeeee;
}
hr {
    border: none 0;
    border-top: 1px solid #aaa;
    height: 1px;
}
div.summary {
    font-size: .8em;
    border-bottom: 1px solid #aaa;
    padding-left: 1em;
    padding-right: 1em;
}
div.summary h2 {
    margin-bottom: 0.3em;
}
div.summary table th {
    text-align: right;
    vertical-align: top;
    white-space: nowrap;
}
span.lineschanged {
    font-size: 0.7em;
}
span.oldmarker {
    color: red;
    font-size: large;
    font-weight: bold;
}
span.newmarker {
    color: green;
    font-size: large;
    font-weight: bold;
}
span.removed {
    color: brown;
}
span.changed {
    color: blue;
}
span.new {
    color: blue;
    font-weight: bold;
}
a.print { font-size: x-small; }

</style>

<style type="text/css" media="print">
pre { font-size: 0.8em; font-family: courier, monospace; }
span.removed { color: #444; font-style: italic }
span.changed { font-weight: bold; }
span.new { font-weight: bold; }
span.newmarker { font-size: 1.2em; font-weight: bold; }
span.oldmarker { font-size: 1.2em; font-weight: bold; }
a.print {display: none}
hr { border: none 0; border-top: 1px solid #aaa; height: 1px; }
</style>

<title>Webrevs</title>
</head>
<body id="SUNWwebrev">
<h2>Webrevs</h2>
<ul>
"""

suffix = """
</ul>
</body>
</html>
"""

my_dir = dirname(__file__)
with open(join(my_dir, 'index.html'), 'w') as fp:
    print(prefix, file=fp)
    for name in sorted(os.listdir(my_dir), reverse=True):
        index = join(my_dir, name, 'index.html')
        if exists(index):
            print('<li><a href="{}/index.html">{}</a></li>'.format(name, name), file=fp)
    print(suffix, file=fp)
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
#
# Copyright 2023 Alibaba Group Holding Limited. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""Group of commands for GraphScope development"""

import io
import os
import subprocess

import click

scripts_dir = os.path.join(os.path.dirname(os.path.realpath(__file__)), "..", "scripts")
install_deps_script = os.path.join(scripts_dir, "install_deps_command.sh")
make_script = os.path.join(scripts_dir, "make_command.sh")
make_image_script = os.path.join(scripts_dir, "make_image_command.sh")
test_script = os.path.join(scripts_dir, "test_command.sh")
default_graphscope_repo_path = os.path.join(
    os.path.dirname(os.path.realpath(__file__)),
    "..",
    "..",
    "..",
    "..",
)


def run_shell_cmd(cmd, workingdir):
    """wrapper function to run a shell command/scripts."""
    click.echo(f"run a shell command on cwd={workingdir}. \ncmd=\"{' '.join(cmd)}\"")
    proc = subprocess.Popen(
        cmd, cwd=workingdir, env=os.environ.copy(), stdout=subprocess.PIPE
    )
    for line in io.TextIOWrapper(proc.stdout, encoding="utf-8"):
        print(line.rstrip())


@click.group()
def cli():
    # nothing happens
    pass


@cli.group()
def flexbuild():
    """Build a customized stack using specific components."""
    pass


@flexbuild.command()
@click.option(
    "--app",
    type=click.Choice(["docker"]),
    required=True,
    help="Applicatin type of the built artifacts you want to build",
)
@click.option(
    "--graphscope-repo",
    required=False,
    help="GraphScope code repo location.",
)
def interactive(app, graphscope_repo):
    """Build Interactive for high throughput scenarios"""
    if graphscope_repo is None:
        graphscope_repo = default_graphscope_repo_path
    interactive_build_dir = os.path.join(
        graphscope_repo, "flex", "interactive", "docker"
    )
    if not os.path.exists(interactive_build_dir) or not os.path.isdir(
        interactive_build_dir
    ):
        click.secho(
            f"No such file or directory {interactive_build_dir}, try --graphscope-repo param.",
            fg="red",
        )
        return
    cmd = ["make", "interactive-runtime", "ENABLE_COORDINATOR=true"]
    run_shell_cmd(cmd, os.path.join(graphscope_repo, interactive_build_dir))


@click.command()
@click.argument(
    "type",
    type=click.Choice(
        ["dev", "client"],
        case_sensitive=False,
    ),
    required=True,
)
@click.option(
    "--graphscope-repo",
    envvar="GRAPHSCOPE_REPO",
    type=click.Path(),
    default=os.path.abspath("."),
    show_default=True,
    help="GraphScope code repo location.",
)
@click.option(
    "--cn",
    is_flag=True,
    default=False,
    help="Whether to use CN located mirrors to speed up download.",
)
@click.option(
    "--install-prefix",
    type=click.Path(),
    default="/opt/graphscope",
    show_default=True,
    help="Install built binaries to customized location.",
)
@click.option(
    "--from-local",
    type=click.Path(),
    default="/tmp/gs-local-deps",
    show_default=True,
    help="""Find raw dependencies of GraphScope from a local directory. The raw
    dependencies would then be built and installed to [prefix]. If the directory
    is empty or not exists, dependency files would be downloaded to [directory].""",
)
@click.option(
    "--v6d-version",
    default="main",
    show_default=True,
    help="v6d version to clone.",
)
@click.option(
    "-j",
    "--jobs",
    default="2",
    help="Concurrent jobs in building, i.e., -j argument passed to make.",
)
@click.option(
    "--for-analytical",
    is_flag=True,
    default=False,
    help="Only install analytical engine dependencies.",
)
@click.option(
    "--no-v6d",
    is_flag=True,
    default=False,
    help="Do not install v6d, for build base docker images, could only be used with '--for-analytical'",
)
def install_deps(
    type,
    graphscope_repo,
    cn,
    install_prefix,
    from_local,
    v6d_version,
    jobs,
    for_analytical,
    no_v6d,
):
    """Install dependencies for building GraphScope."""
    cmd = [
        "bash",
        install_deps_script,
        "-t",
        type,
        "-i",
        install_prefix,
        "-d",
        from_local,
        "-v",
        str(v6d_version),
        "-j",
        str(jobs),
    ]
    if for_analytical:
        cmd.append("--for-analytical")
    if no_v6d:
        if not for_analytical:
            # could only be used with '--for-analytical'
            raise RuntimeError("Missing --for-analytical with --no-v6d parameter")
        cmd.append("--no-v6d")
    if cn:
        cmd.append("--cn")
    run_shell_cmd(cmd, graphscope_repo)


@click.command()
@click.argument(
    "component",
    type=click.Choice(
        [
            "interactive",
            "interactive-install",
            "analytical",
            "analytical-java-install",
            "analytical-install",
            "learning",
            "learning-install",
            "coordinator",
            "client",
            "clean",
            "all",
        ],
        case_sensitive=False,
    ),
    required=False,
)
@click.option(
    "--graphscope-repo",
    envvar="GRAPHSCOPE_REPO",
    type=click.Path(),
    default=os.path.abspath("."),
    show_default=True,
    help="GraphScope code repo location.",
)
@click.option(
    "--install-prefix",
    type=click.Path(),
    default="/opt/graphscope",
    show_default=True,
    help="Install built binaries to customized location.",
)
@click.option(
    "--storage-type",
    default="default",
    help="Make gie with specified storage type.",
)
def make(component, graphscope_repo, install_prefix, storage_type):
    """Build executive binaries of COMPONENT. If not given a specific component, build all.
    \f
    TODO: maybe without make?
    """
    click.secho(
        "Before making artifacts, please manually source ENVs from ~/.graphscope_env.",
        fg="yellow",
    )
    click.secho(
        f"Begin the make command, to build components [{component}] of GraphScope, with repo = {graphscope_repo}",
        fg="green",
    )
    if component is None:
        component = "all"

    cmd = [
        "bash",
        make_script,
        "-c",
        component,
        "-i",
        install_prefix,
        "-s",
        storage_type,
    ]
    run_shell_cmd(cmd, graphscope_repo)


@click.command()
@click.argument(
    "component",
    type=click.Choice(
        [
            "all",
            "graphscope-dev",
            "coordinator",
            "analytical",
            "analytical-java",
            "interactive",
            "interactive-frontend",
            "interactive-executor",
            "learning,",
            "vineyard-dev",
            "vineyard-runtime",
            "manylinux2014-ext",
        ],
        case_sensitive=False,
    ),
    required=False,
)
@click.option(
    "--graphscope-repo",
    envvar="GRAPHSCOPE_REPO",
    type=click.Path(),
    default=os.path.abspath("."),
    show_default=True,
    help="GraphScope code repo location.",
)
@click.option(
    "--tag",
    default="latest",
    show_default=True,
    help="image tag name to build",
)
@click.option(
    "--registry",
    default="registry.cn-hongkong.aliyuncs.com",
    show_default=True,
    help="registry name",
)
def make_image(component, graphscope_repo, registry, tag):
    """Make docker images from source code for deployment.
    \f
    TODO: fulfill this.
    """
    if component is None:
        component = "all"

    cmd = ["bash", make_image_script, "-c", component, "-r", registry, "-t", tag]
    run_shell_cmd(cmd, graphscope_repo)


@click.command()
@click.argument(
    "type",
    type=click.Choice(
        [
            "analytical",
            "analytical-java",
            "interactive",
            "learning",
            "local-e2e",
            "k8s-e2e",
            "groot",
        ],
        case_sensitive=False,
    ),
    required=False,
)
@click.option(
    "--graphscope-repo",
    envvar="GRAPHSCOPE_REPO",
    type=click.Path(),
    default=os.path.abspath("."),
    show_default=True,
    help="GraphScope code repo location.",
)
@click.option(
    "--testdata",
    type=click.Path(),
    default="/tmp/gstest",
    show_default=True,
    help="""assign a custom test data location. This could be cloned from
    https://github.com/graphscope/gstest""",
)
@click.option(
    "--local",
    is_flag=True,
    default=False,
    help="Run local tests",
)
@click.option(
    "--storage-type",
    default="default",
    show_default=True,
    help="test gie with specified storage type",
)
@click.option(
    "--k8s",
    is_flag=True,
    default=False,
    help="Run local tests",
)
@click.option(
    "--nx",
    is_flag=True,
    default=False,
    help="Run nx tests",
)
def test(type, graphscope_repo, testdata, local, storage_type, k8s, nx):
    """Trigger tests on built artifacts.

    \f
    TODO: fulfill this."""
    click.secho(f"graphscope_repo = {graphscope_repo}", fg="green")
    click.echo("test")
    if type is None:
        type = ""
    cmd = [
        "bash",
        test_script,
        "-t",
        type,
        "-d",
        testdata,
        "-l",
        str(local),
        "-s",
        storage_type,
        "-k",
        str(k8s),
        "-n",
        str(nx),
    ]
    run_shell_cmd(cmd, graphscope_repo)


cli.add_command(install_deps)
cli.add_command(make)
cli.add_command(make_image)
cli.add_command(test)

if __name__ == "__main__":
    cli()
